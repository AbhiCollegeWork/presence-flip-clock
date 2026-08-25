package com.presenceflipclock

import android.app.admin.DeviceAdminReceiver

/**
 * Minimal Device Admin. Its ONLY declared policy is force-lock (see res/xml/device_admin.xml),
 * used solely to turn the screen off when the room is empty in "deep power-off" mode. It never
 * wipes, never sets a password, never locks anything the user did not ask for.
 */
class ClockDeviceAdminReceiver : DeviceAdminReceiver()
