package com.biliaudio.app;

import android.content.Context;

import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class LocalTranscription {
    private static final String MODEL_ROOT = "https://huggingface.co/csukuangfj/"
            + "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/";
    private static final String MODEL_HASH =
            "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51";
    private static final String TOKENS_HASH =
            "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc";
    private static final Object MODELS = new Object();

    static boolean hasModel(Context context) {
        return new File(context.getFilesDir(), "asr/model.int8.onnx").isFile()
                && new File(context.getFilesDir(), "asr/tokens.txt").isFile();
    }

    static List<AudioLibrary.Cue> transcribe(Context context, File audio, int duration,
                                           IntConsumer modelProgress, Runnable modelReady,
                                           IntConsumer progress)
            throws IOException, JSONException {
        File directory = modelDirectory(context);
        File model;
        File tokens;
        synchronized (MODELS) {
            model = ensureModelFile(directory, "model.int8.onnx", MODEL_HASH, modelProgress);
            tokens = ensureModelFile(directory, "tokens.txt", TOKENS_HASH, ignored -> {});
        }
        modelReady.run();
        OfflineRecognizerConfig config = new OfflineRecognizerConfig();
        OfflineSenseVoiceModelConfig senseVoice = config.getModelConfig().getSenseVoice();
        senseVoice.setModel(model.getPath());
        senseVoice.setLanguage("zh");
        senseVoice.setUseInverseTextNormalization(true);
        config.getModelConfig().setTokens(tokens.getPath());
        config.getModelConfig().setNumThreads(2);

        File checkpoint = new File(audio.getPath() + ".asr");
        Transcript transcript = new Transcript(checkpoint);
        OfflineRecognizer recognizer = new OfflineRecognizer(null, config);
        try {
            AudioFiles.decode(audio, (samples, start, length) -> {
                if (start <= transcript.lastWindow) {
                    return;
                }
                OfflineStream stream = recognizer.createStream();
                try {
                    stream.acceptWaveform(samples, AudioFiles.ASR_RATE);
                    recognizer.decode(stream);
                    transcript.append(recognizer.getResult(stream), start, length);
                } finally {
                    stream.release();
                }
                transcript.lastWindow = start;
                if (transcript.lastWindow % 51000 == 0) {
                    transcript.save();
                }
                progress.accept((int) Math.min(99, (long) (start + length) * 100 / duration));
            });
            transcript.save();
            return transcript.cues;
        } catch (IOException error) {
            transcript.save();
            throw error;
        } finally {
            recognizer.release();
        }
    }

    static void clearCheckpoint(File audio) {
        new android.util.AtomicFile(new File(audio.getPath() + ".asr")).delete();
    }

    private static File modelDirectory(Context context) throws IOException {
        File directory = new File(context.getFilesDir(), "asr");
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("无法创建转写模型目录。");
        }
        return directory;
    }

    static void importModel(Context context, InputStream input) throws IOException {
        synchronized (MODELS) {
            File directory = modelDirectory(context);
            File model = new File(directory, "model.int8.onnx.import");
            File tokens = new File(directory, "tokens.txt.import");
            boolean hasModel = false;
            boolean hasTokens = false;
            try (ZipInputStream zip = new ZipInputStream(input)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (!entry.isDirectory()) {
                        String name = entry.getName();
                        name = name.substring(name.lastIndexOf('/') + 1);
                        if (name.equals("model.int8.onnx")) {
                            copyModelEntry(zip, model, 239233841, MODEL_HASH);
                            hasModel = true;
                        } else if (name.equals("tokens.txt")) {
                            copyModelEntry(zip, tokens, 315894, TOKENS_HASH);
                            hasTokens = true;
                        }
                    }
                    zip.closeEntry();
                }
                if (!hasModel || !hasTokens) {
                    throw new IOException("ZIP 中需要包含 model.int8.onnx 和 tokens.txt。");
                }
                if (!model.renameTo(new File(directory, "model.int8.onnx"))
                        || !tokens.renameTo(new File(directory, "tokens.txt"))) {
                    throw new IOException("转写模型保存失败。");
                }
            } finally {
                model.delete();
                tokens.delete();
            }
        }
    }

    private static void copyModelEntry(InputStream input, File target, int length, String expected) throws IOException {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
            byte[] buffer = new byte[65536];
            long total = 0;
            int size;
            while ((size = input.read(buffer)) != -1) {
                total += size;
                if (total > length) {
                    throw new IOException("模型包与所需版本不符。");
                }
                out.write(buffer, 0, size);
            }
        }
        verifyModel(target, expected);
    }

    private static File ensureModelFile(File directory, String name, String expectedHash,
                                        IntConsumer progress) throws IOException {
        File output = new File(directory, name);
        if (output.isFile()) {
            return output;
        }
        File partial = new File(directory, name + ".part");
        try {
            BilibiliClient.download(MODEL_ROOT + name, partial, progress);
        } catch (IOException error) {
            BilibiliClient.checkInterrupted();
            throw new IOException("无法下载 AI 字幕模型。可在账号页导入离线模型 ZIP 后重试字幕。", error);
        }
        try {
            verifyModel(partial, expectedHash);
        } catch (IOException error) {
            if (!Thread.currentThread().isInterrupted()) {
                partial.delete();
            }
            throw error;
        }
        if (!partial.renameTo(output)) {
            throw new IOException("转写模型保存失败。");
        }
        return output;
    }

    private static void verifyModel(File file, String expectedHash) throws IOException {
        MessageDigest hash;
        try {
            hash = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("无法校验转写模型。", error);
        }
        try (FileInputStream stream = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                BilibiliClient.checkInterrupted();
                hash.update(buffer, 0, count);
            }
        }
        StringBuilder actualHash = new StringBuilder();
        for (byte value : hash.digest()) {
            actualHash.append(String.format(Locale.US, "%02x", value & 255));
        }
        if (!expectedHash.contentEquals(actualHash)) {
            throw new IOException("模型文件校验失败，请重试。");
        }
    }

    private static final class Transcript {
        final List<AudioLibrary.Cue> cues = new ArrayList<>();
        final File checkpoint;
        int lastWindow = -1;

        Transcript(File checkpoint) throws IOException, JSONException {
            this.checkpoint = checkpoint;
            if (checkpoint.isFile()) {
                JSONObject stored = AudioLibrary.read(checkpoint);
                lastWindow = stored.getInt("window");
                JSONArray entries = stored.getJSONArray("cues");
                for (int index = 0; index < entries.length(); index++) {
                    JSONArray entry = entries.getJSONArray(index);
                    cues.add(new AudioLibrary.Cue(entry.getInt(0), entry.getInt(1),
                            entry.getString(2)));
                }
            }
        }

        void append(OfflineRecognizerResult result, int window, int duration) {
            String[] tokens = result.getTokens();
            float[] times = result.getTimestamps();
            int size = Math.min(tokens.length, times.length);
            StringBuilder line = new StringBuilder();
            int start = window;
            int lastToken = start;
            int floor = cues.isEmpty() ? 0 : cues.get(cues.size() - 1).end() - 120;
            for (int index = 0; index < size; index++) {
                int time = window + Math.max(0, Math.round(times[index] * 1000));
                if (time < floor || time >= window + duration) {
                    continue;
                }
                if (line.length() > 0 && (time - lastToken > 800
                        || (line.length() >= 38 && time - start >= 1900))) {
                    add(line, start, Math.min(time, lastToken + 450));
                }
                if (line.length() == 0) {
                    start = time;
                }
                String token = tokens[index].replace('▁', ' ');
                line.append(token);
                lastToken = time;
                if (!token.isEmpty() && "，。！？；：,.!?;".contains(token)
                        && (line.length() >= 8 || line.toString().startsWith("第"))) {
                    int next = index + 1 < size ? window + Math.round(times[index + 1] * 1000)
                            : window + duration;
                    add(line, start, Math.min(next, lastToken + 400));
                }
            }
            if (line.length() == 0 && size == 0 && !result.getText().trim().isEmpty()) {
                start = Math.max(window, floor);
                lastToken = start + 1500;
                line.append(result.getText());
            }
            add(line, start, Math.min(window + duration, lastToken + 400));
        }

        void add(StringBuilder line, int start, int end) {
            String text = line.toString().trim();
            if (text.codePoints().anyMatch(Character::isLetterOrDigit)) {
                cues.add(new AudioLibrary.Cue(start, Math.max(start + 60, end), text));
            }
            line.setLength(0);
        }

        void save() throws IOException {
            JSONArray entries = new JSONArray();
            for (AudioLibrary.Cue cue : cues) {
                entries.put(new JSONArray().put(cue.start()).put(cue.end()).put(cue.text()));
            }
            try {
                AudioLibrary.write(checkpoint, new JSONObject().put("window", lastWindow)
                        .put("cues", entries));
            } catch (JSONException error) {
                throw new IOException("字幕进度保存失败。", error);
            }
        }
    }
}
