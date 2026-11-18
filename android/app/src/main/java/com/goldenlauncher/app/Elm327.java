package com.goldenlauncher.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Protocolo del ELM327 (misma lógica que bridge.py). */
class Elm327 {
    static class EngineOff extends IOException {
        EngineOff() {
            super("La ECU no responde");
        }
    }

    interface Logger {
        void log(String s);
    }

    private final Link link;
    private final Logger logger;
    boolean isCan = true;
    boolean verbose = true;   // registra cada comando y respuesta (solo al conectar y en la 1.ª lectura)
    String protocol = "?";

    Elm327(Link link, Logger logger) {
        this.link = link;
        this.logger = logger;
    }

    List<String> cmd(String c, long timeoutMs) throws IOException {
        link.clearInput();
        link.write((c + "\r").getBytes(StandardCharsets.US_ASCII));
        String txt = link.readUntilPrompt(timeoutMs).replace('\r', '\n');
        List<String> out = new ArrayList<>();
        for (String l : txt.split("\n")) {
            l = l.trim();
            if (l.isEmpty() || l.equalsIgnoreCase(c) || l.startsWith("SEARCHING")) continue;
            out.add(l);
        }
        if (verbose && logger != null) {
            String r = String.join(" | ", out);
            logger.log(c + " → " + (r.isEmpty() ? "(vacío)" : r.length() > 60 ? r.substring(0, 60) + "…" : r));
        }
        return out;
    }

    void init() throws IOException {
        cmd("ATZ", 6000);
        for (String c : new String[]{"ATE0", "ATL0", "ATS0", "ATH0", "ATSP0"}) cmd(c, 3000);
        cmd("0100", 15000); // fuerza la búsqueda del protocolo
        StringBuilder p = new StringBuilder();
        for (String l : cmd("ATDPN", 3000)) p.append(l);
        String proto = p.toString().trim().toUpperCase(Locale.ROOT);
        if (proto.startsWith("A")) proto = proto.substring(1);
        protocol = proto;
        isCan = !proto.isEmpty() && "6789ABC".indexOf(proto.charAt(0)) >= 0;
    }

    /** Bytes de datos de un PID de modo 01, o null si el vehículo no lo reporta. */
    int[] query(String pid) throws IOException {
        List<String> lines = cmd("01" + pid, 8000);
        String joined = String.join(" ", lines).toUpperCase(Locale.ROOT);
        if (joined.contains("UNABLE TO CONNECT") || (joined.contains("BUS INIT") && joined.contains("ERROR")))
            throw new EngineOff();
        for (String l : lines) {
            l = l.replace(" ", "").toUpperCase(Locale.ROOT);
            int i = l.indexOf("41" + pid);
            if (i >= 0) {
                String h = l.substring(i + 4);
                int n = h.length() / 2;
                int[] b = new int[n];
                try {
                    for (int k = 0; k < n; k++) b[k] = Integer.parseInt(h.substring(2 * k, 2 * k + 2), 16);
                } catch (NumberFormatException e) {
                    return null;
                }
                return b;
            }
        }
        return null;
    }

    /** Códigos de avería almacenados (modo 03). */
    List<String> readDtc() throws IOException {
        List<String> lines = cmd("03", 8000);
        StringBuilder data = new StringBuilder();
        List<String> chunks = new ArrayList<>();
        for (String l : lines) {
            l = l.replace(" ", "").toUpperCase(Locale.ROOT);
            if (l.equals("NODATA") || l.equals("?") || l.equals("STOPPED")) continue;
            if (l.matches("[0-9A-F]{3}")) continue;                 // cabecera de longitud (multi-trama CAN)
            if (l.matches("^[0-9A-F]:.*")) data.append(l.substring(2)); // "0:4302..."
            else if (l.matches("[0-9A-F]+")) chunks.add(l);
        }
        String payload;
        if (isCan) {
            String all = data.length() > 0 ? data.toString() : String.join("", chunks);
            int i = all.indexOf("43");
            if (i < 0) return new ArrayList<>();
            all = all.substring(i + 2);
            int n = all.length() >= 2 ? Integer.parseInt(all.substring(0, 2), 16) : 0;
            payload = all.substring(Math.min(2, all.length()), Math.min(all.length(), 2 + n * 4));
        } else {
            StringBuilder sb = new StringBuilder();
            for (String c : chunks) if (c.startsWith("43")) sb.append(c.substring(2));
            payload = sb.toString();
        }
        List<String> codes = new ArrayList<>();
        for (int k = 0; k + 4 <= payload.length(); k += 4) {
            String g = payload.substring(k, k + 4);
            if (g.equals("0000")) continue;
            int v = Integer.parseInt(g, 16);
            String code = "PCBU".charAt(v >> 14) + Integer.toString((v >> 12) & 3) + g.substring(1);
            if (!codes.contains(code)) codes.add(code);
        }
        return codes;
    }
}
