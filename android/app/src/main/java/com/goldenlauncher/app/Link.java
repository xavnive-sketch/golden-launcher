package com.goldenlauncher.app;

import java.io.IOException;

/**
 * Conexión con el adaptador ELM327 (Bluetooth clásico, BLE o Wi-Fi).
 * Las subclases entregan los bytes recibidos con {@link #onBytes}; el ELM327 termina
 * cada respuesta con el carácter '>' (prompt).
 */
abstract class Link {
    private final StringBuilder buf = new StringBuilder();
    protected volatile boolean closed = false;

    abstract void open() throws IOException;

    abstract void write(byte[] data) throws IOException;

    abstract void close();

    abstract String describe();

    protected void onBytes(byte[] data, int len) {
        if (data == null) return;
        synchronized (buf) {
            for (int i = 0; i < len; i++) buf.append((char) (data[i] & 0xff));
            buf.notifyAll();
        }
    }

    protected void markClosed() {
        closed = true;
        synchronized (buf) {
            buf.notifyAll();
        }
    }

    void clearInput() {
        synchronized (buf) {
            buf.setLength(0);
        }
    }

    /** Espera hasta recibir '>' y devuelve el texto anterior. */
    String readUntilPrompt(long timeoutMs) throws IOException {
        long end = System.currentTimeMillis() + timeoutMs;
        synchronized (buf) {
            while (buf.indexOf(">") < 0) {
                if (closed) throw new IOException("Se perdió la conexión con el adaptador");
                long left = end - System.currentTimeMillis();
                if (left <= 0) throw new IOException("El adaptador no respondió a tiempo");
                try {
                    buf.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrumpido");
                }
            }
            int i = buf.indexOf(">");
            String out = buf.substring(0, i);
            buf.delete(0, i + 1);
            return out;
        }
    }
}
