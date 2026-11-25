package com.goldenlauncher.app;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayList;
import java.util.Locale;

/** Voz de J.A.R.V.I.S.: hablar (TextToSpeech) y escuchar (SpeechRecognizer), en español. */
class Voice {
    interface Events {
        void emit(String type, String text);   // listening, partial, final, error, end, speak-start, speak-end
    }

    private final Context ctx;
    private final Events ev;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private SpeechRecognizer rec;

    Voice(Context ctx, Events ev) {
        this.ctx = ctx.getApplicationContext();
        this.ev = ev;
        tts = new TextToSpeech(this.ctx, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(new Locale("es", "EC"));
                if (r < 0) r = tts.setLanguage(new Locale("es", "ES"));
                if (r < 0) tts.setLanguage(Locale.getDefault());
                ttsReady = true;
            }
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { ev.emit("speak-start", ""); }
            @Override public void onDone(String id) { ev.emit("speak-end", ""); }
            @Override public void onError(String id) { ev.emit("speak-end", ""); }
        });
    }

    boolean canSpeak() {
        return ttsReady;
    }

    void speak(String text) {
        if (!ttsReady || text == null) return;
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis");
    }

    void stopSpeaking() {
        if (tts != null) tts.stop();
    }

    boolean canListen() {
        return SpeechRecognizer.isRecognitionAvailable(ctx);
    }

    void listen() {
        main.post(() -> {
            stopSpeaking();
            if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
                ev.emit("error", "Esta radio no tiene reconocimiento de voz (instala o activa «Google» / «Servicios de voz de Google»).");
                return;
            }
            if (rec != null) rec.destroy();
            rec = SpeechRecognizer.createSpeechRecognizer(ctx);
            rec.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle b) { ev.emit("listening", ""); }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float v) { }
                @Override public void onBufferReceived(byte[] b) { }
                @Override public void onEndOfSpeech() { ev.emit("end", ""); }
                @Override public void onError(int e) {
                    String m = e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ? "No te escuché, intenta de nuevo."
                            : e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ? "Falta el permiso del micrófono."
                            : e == SpeechRecognizer.ERROR_NETWORK || e == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ? "El reconocimiento de voz necesita internet."
                            : "Error de reconocimiento de voz (" + e + ").";
                    ev.emit("error", m);
                }
                @Override public void onResults(Bundle b) {
                    ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    ev.emit("final", r != null && !r.isEmpty() ? r.get(0) : "");
                }
                @Override public void onPartialResults(Bundle b) {
                    ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (r != null && !r.isEmpty()) ev.emit("partial", r.get(0));
                }
                @Override public void onEvent(int t, Bundle b) { }
            });
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-EC");
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            rec.startListening(i);
        });
    }

    void stopListening() {
        main.post(() -> { if (rec != null) rec.stopListening(); });
    }

    void shutdown() {
        main.post(() -> { if (rec != null) rec.destroy(); rec = null; });
        if (tts != null) tts.shutdown();
    }
}
