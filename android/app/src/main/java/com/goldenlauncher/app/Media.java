package com.goldenlauncher.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.provider.Settings;
import android.util.Base64;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Apps instaladas (cajón de apps) y control de la música que suena en otras apps. */
class Media {
    private final Context ctx;
    private String artKey = null, artData = null;

    Media(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    // ---------------- Apps instaladas ----------------

    JSONArray listApps() {
        PackageManager pm = ctx.getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(i, 0);
        List<JSONObject> out = new ArrayList<>();
        for (ResolveInfo r : list) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName())) continue;
            try {
                JSONObject o = new JSONObject();
                o.put("pkg", pkg);
                o.put("name", String.valueOf(r.loadLabel(pm)));
                o.put("icon", toDataUrl(r.loadIcon(pm), 96));
                ApplicationInfo ai = r.activityInfo.applicationInfo;
                o.put("system", (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                out.add(o);
            } catch (Exception ignored) {
            }
        }
        out.sort((a, b) -> a.optString("name").compareToIgnoreCase(b.optString("name")));
        JSONArray arr = new JSONArray();
        for (JSONObject o : out) arr.put(o);
        return arr;
    }

    boolean launch(String pkg) {
        Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        ctx.startActivity(i);
        return true;
    }

    private static String toDataUrl(Drawable d, int size) {
        Bitmap b;
        if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
            b = Bitmap.createScaledBitmap(((BitmapDrawable) d).getBitmap(), size, size, true);
        } else {
            b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, size, size);
            d.draw(c);
        }
        return toDataUrl(b, 80);
    }

    private static String toDataUrl(Bitmap b, int quality) {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, quality, os);
        return "data:image/png;base64," + Base64.encodeToString(os.toByteArray(), Base64.NO_WRAP);
    }

    // ---------------- Música ----------------

    boolean hasNotificationAccess() {
        String flat = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(ctx.getPackageName());
    }

    void openNotificationAccess() {
        Intent i = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (Exception e) {
            Intent s = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(s);
        }
    }

    private MediaController controller() {
        if (!hasNotificationAccess()) return null;
        try {
            MediaSessionManager m = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> l = m.getActiveSessions(new ComponentName(ctx, MediaListener.class));
            if (l == null || l.isEmpty()) return null;
            for (MediaController c : l) {
                PlaybackState st = c.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) return c;
            }
            return l.get(0);
        } catch (SecurityException e) {
            return null;
        }
    }

    JSONObject info() {
        JSONObject o = new JSONObject();
        try {
            o.put("access", hasNotificationAccess());
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            o.put("vol", am.getStreamVolume(AudioManager.STREAM_MUSIC));
            o.put("volMax", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            o.put("anyPlaying", am.isMusicActive());
            MediaController c = controller();
            if (c == null) return o;
            String pkg = c.getPackageName();
            o.put("pkg", pkg);
            try {
                PackageManager pm = ctx.getPackageManager();
                o.put("app", String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))));
            } catch (PackageManager.NameNotFoundException e) {
                o.put("app", pkg);
            }
            PlaybackState st = c.getPlaybackState();
            if (st != null) {
                o.put("playing", st.getState() == PlaybackState.STATE_PLAYING);
                long pos = st.getPosition();
                if (st.getState() == PlaybackState.STATE_PLAYING && st.getLastPositionUpdateTime() > 0)
                    pos += (long) ((android.os.SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
                o.put("pos", Math.max(0, pos));
            }
            MediaMetadata md = c.getMetadata();
            if (md != null) {
                String title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
                String artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
                if (artist == null) artist = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
                o.put("title", title == null ? "" : title);
                o.put("artist", artist == null ? "" : artist);
                String album = md.getString(MediaMetadata.METADATA_KEY_ALBUM);
                o.put("album", album == null ? "" : album);
                o.put("dur", md.getLong(MediaMetadata.METADATA_KEY_DURATION));
                String key = pkg + "|" + title + "|" + artist;
                if (!key.equals(artKey)) {
                    artKey = key;
                    artData = null;
                    Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                    if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
                    if (art != null) {
                        Bitmap small = Bitmap.createScaledBitmap(art, 256, Math.max(1, art.getHeight() * 256 / Math.max(1, art.getWidth())), true);
                        ByteArrayOutputStream os = new ByteArrayOutputStream();
                        small.compress(Bitmap.CompressFormat.JPEG, 80, os);
                        artData = "data:image/jpeg;base64," + Base64.encodeToString(os.toByteArray(), Base64.NO_WRAP);
                    }
                }
                if (artData != null) o.put("art", artData);
            }
        } catch (JSONException ignored) {
        }
        return o;
    }

    /** action: play, pause, toggle, next, prev */
    void control(String action) {
        MediaController c = controller();
        if (c != null) {
            MediaController.TransportControls t = c.getTransportControls();
            PlaybackState st = c.getPlaybackState();
            boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
            switch (action) {
                case "next": t.skipToNext(); return;
                case "prev": t.skipToPrevious(); return;
                case "play": t.play(); return;
                case "pause": t.pause(); return;
                default: if (playing) t.pause(); else t.play(); return;
            }
        }
        // Sin acceso a notificaciones: teclas multimedia (las recibe la app que suena)
        int code = "next".equals(action) ? KeyEvent.KEYCODE_MEDIA_NEXT
                : "prev".equals(action) ? KeyEvent.KEYCODE_MEDIA_PREVIOUS
                : "play".equals(action) ? KeyEvent.KEYCODE_MEDIA_PLAY
                : "pause".equals(action) ? KeyEvent.KEYCODE_MEDIA_PAUSE : KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }

    void volume(int dir) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
    }

    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
