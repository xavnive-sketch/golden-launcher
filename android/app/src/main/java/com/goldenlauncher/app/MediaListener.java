package com.goldenlauncher.app;

import android.service.notification.NotificationListenerService;

/**
 * Servicio vacío: Android solo deja leer "lo que está sonando" (MediaSession de Spotify, radio, etc.)
 * a las apps con acceso a notificaciones. El usuario lo activa una vez en Ajustes de Android.
 */
public class MediaListener extends NotificationListenerService {
}
