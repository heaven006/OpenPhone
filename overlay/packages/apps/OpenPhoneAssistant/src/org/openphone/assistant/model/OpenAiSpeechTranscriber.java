package org.openphone.assistant.model;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class OpenAiSpeechTranscriber {
    private static final String MODEL = "gpt-4o-mini-transcribe";
    private static final int SAMPLE_RATE = 16000;
    private static final int MIN_RECORD_MILLIS = 900;
    private static final int INITIAL_SPEECH_TIMEOUT_MILLIS = 4500;
    // Mid-sentence pauses are often 1.2 — 1.6s. 1100ms was cutting people off
    // mid-thought; 1700ms still feels responsive but lets natural pauses
    // through.
    private static final int END_SILENCE_MILLIS = 1700;
    private static final int SPEECH_RMS_THRESHOLD = 900;

    private final ModelEndpointConfig mEndpointConfig;
    private volatile boolean mCancelled;
    private volatile AudioRecord mCurrentRecorder;
    private volatile HttpURLConnection mCurrentConnection;

    public OpenAiSpeechTranscriber(String apiKey) {
        this(ModelEndpointConfig.directOpenAi(apiKey));
    }

    public OpenAiSpeechTranscriber(ModelEndpointConfig endpointConfig) {
        mEndpointConfig = endpointConfig == null
                ? ModelEndpointConfig.directOpenAi("") : endpointConfig;
    }

    public static String providerDisplayName() {
        return "OpenAI audio transcription";
    }

    public static String modelName() {
        return MODEL;
    }

    public static String privacyDisclosure() {
        return "Voice start records a short command and sends it to OpenAI transcription. "
                + "The transcript becomes the task text; audio is not stored by OpenPhone.";
    }

    public String recordAndTranscribe(int maxMillis) throws IOException {
        if (!mEndpointConfig.isConfigured()) {
            throw new IOException(mEndpointConfig.missingCredentialReason());
        }
        byte[] wav = recordWav(maxMillis);
        if (mCancelled) {
            throw new IOException("voice_cancelled");
        }
        return transcribe(wav);
    }

    public void cancel() {
        mCancelled = true;
        AudioRecord recorder = mCurrentRecorder;
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (IllegalStateException ignored) {
            }
            recorder.release();
        }
        HttpURLConnection connection = mCurrentConnection;
        if (connection != null) {
            connection.disconnect();
        }
    }

    private byte[] recordWav(int maxMillis) throws IOException {
        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufferSize = Math.max(minBuffer, SAMPLE_RATE / 5);
        AudioRecord recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                bufferSize);
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            throw new IOException("microphone_unavailable");
        }

        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        byte[] buffer = new byte[bufferSize];
        long startedAt = System.currentTimeMillis();
        long endAt = System.currentTimeMillis() + maxMillis;
        long lastVoiceAt = 0;
        boolean heardVoice = false;
        try {
            mCurrentRecorder = recorder;
            recorder.startRecording();
            while (!mCancelled && System.currentTimeMillis() < endAt) {
                int read = recorder.read(buffer, 0, buffer.length);
                if (read > 0) {
                    pcm.write(buffer, 0, read);
                    long now = System.currentTimeMillis();
                    if (rms16Bit(buffer, read) >= SPEECH_RMS_THRESHOLD) {
                        heardVoice = true;
                        lastVoiceAt = now;
                    }
                    if (now - startedAt >= MIN_RECORD_MILLIS) {
                        if (heardVoice && now - lastVoiceAt >= END_SILENCE_MILLIS) {
                            break;
                        }
                        if (!heardVoice && now - startedAt >= INITIAL_SPEECH_TIMEOUT_MILLIS) {
                            break;
                        }
                    }
                }
            }
        } finally {
            try {
                recorder.stop();
            } catch (IllegalStateException ignored) {
            }
            try {
                recorder.release();
            } catch (RuntimeException ignored) {
            }
            if (mCurrentRecorder == recorder) {
                mCurrentRecorder = null;
            }
        }
        if (mCancelled) {
            throw new IOException("voice_cancelled");
        }
        return wavFromPcm(pcm.toByteArray());
    }

    private static int rms16Bit(byte[] bytes, int length) {
        long sumSquares = 0;
        int samples = 0;
        for (int i = 0; i + 1 < length; i += 2) {
            int sample = (bytes[i] & 0xff) | (bytes[i + 1] << 8);
            sumSquares += (long) sample * sample;
            samples++;
        }
        if (samples == 0) {
            return 0;
        }
        return (int) Math.sqrt(sumSquares / (double) samples);
    }

    private String transcribe(byte[] wav) throws IOException {
        String boundary = "OpenPhoneBoundary" + System.currentTimeMillis();
        HttpURLConnection connection = (HttpURLConnection) new URL(
                mEndpointConfig.transcriptionsUrl())
                .openConnection();
        mCurrentConnection = connection;
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setChunkedStreamingMode(0);
        connection.setRequestProperty("Authorization", "Bearer " + mEndpointConfig.bearerToken());
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        connection.setRequestProperty("Accept", "application/json");
        if (mEndpointConfig.isBrokerMode()) {
            connection.setRequestProperty("X-OpenPhone-Model-Provider", "openai_transcription");
            connection.setRequestProperty("X-OpenPhone-Request-Shape", "transcription_proxy");
        }

        try (OutputStream output = connection.getOutputStream()) {
            writeField(output, boundary, "model", MODEL);
            writeFile(output, boundary, "file", "command.wav", "audio/wav", wav);
            output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }

        try {
            int status = connection.getResponseCode();
            String body = readAll(status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream());
            if (mCancelled) {
                throw new IOException("voice_cancelled");
            }
            if (status < 200 || status >= 300) {
                throw new IOException("OpenAI HTTP " + status + ": " + summarizeError(body));
            }
            try {
                return new JSONObject(body).optString("text", "").trim();
            } catch (JSONException e) {
                throw new IOException("bad_transcription_response", e);
            }
        } finally {
            if (mCurrentConnection == connection) {
                mCurrentConnection = null;
            }
        }
    }

    private static void writeField(OutputStream output, String boundary, String name, String value)
            throws IOException {
        output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFile(OutputStream output, String boundary, String name,
            String filename, String mimeType, byte[] bytes) throws IOException {
        output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\""
                + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Type: " + mimeType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(bytes);
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] wavFromPcm(byte[] pcm) throws IOException {
        ByteArrayOutputStream wav = new ByteArrayOutputStream();
        int byteRate = SAMPLE_RATE * 2;
        wav.write("RIFF".getBytes(StandardCharsets.US_ASCII));
        writeIntLe(wav, 36 + pcm.length);
        wav.write("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        writeIntLe(wav, 16);
        writeShortLe(wav, 1);
        writeShortLe(wav, 1);
        writeIntLe(wav, SAMPLE_RATE);
        writeIntLe(wav, byteRate);
        writeShortLe(wav, 2);
        writeShortLe(wav, 16);
        wav.write("data".getBytes(StandardCharsets.US_ASCII));
        writeIntLe(wav, pcm.length);
        wav.write(pcm);
        return wav.toByteArray();
    }

    private static void writeIntLe(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write((value >> 8) & 0xff);
        output.write((value >> 16) & 0xff);
        output.write((value >> 24) & 0xff);
    }

    private static void writeShortLe(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write((value >> 8) & 0xff);
    }

    private static String readAll(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static String summarizeError(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            return "";
        }
        try {
            JSONObject error = new JSONObject(responseBody).optJSONObject("error");
            if (error != null) {
                return error.optString("message", responseBody);
            }
        } catch (JSONException ignored) {
        }
        return responseBody.length() > 500 ? responseBody.substring(0, 500) : responseBody;
    }
}
