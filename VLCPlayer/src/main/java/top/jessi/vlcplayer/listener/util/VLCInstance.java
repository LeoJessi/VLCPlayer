/*****************************************************************************
 * VLCInstance.java
 *****************************************************************************
 * Copyright © 2011-2014 VLC authors and VideoLAN
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *****************************************************************************/

package top.jessi.vlcplayer.listener.util;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.util.VLCUtil;

public class VLCInstance {
    public final static String TAG = "VLC/UiTools/VLCInstance";

    @SuppressLint("StaticFieldLeak")
    private static LibVLC sLibVLC = null;


    /**
     * A set of utility functions for the VLC application
     */
    public synchronized static LibVLC get(Context getApplicationContext) throws IllegalStateException {
        if (sLibVLC == null) {
            //  Thread.setDefaultUncaughtExceptionHandler(new VLCCrashHandler());

            //    final Context context = VLCApplication.getAppContext();
            if (!VLCUtil.hasCompatibleCPU(getApplicationContext)) {
                Log.e(TAG, VLCUtil.getErrorMsg());
                //  throw new IllegalStateException("LibVLC initialisation failed: " + VLCUtil.getErrorMsg());
            }

            sLibVLC = new LibVLC(getApplicationContext, VLCOptions.getLibOptions(getApplicationContext));
            //    VLCApplication.runBackground(sCopyLua);
        }
        return sLibVLC;
    }

    public static synchronized void restart(Context getApplicationContext) throws IllegalStateException {
        if (sLibVLC != null) {
            sLibVLC.release();
            sLibVLC = new LibVLC(getApplicationContext, VLCOptions.getLibOptions(getApplicationContext));
        }
    }

    public static synchronized boolean testCompatibleCPU(Context context) {
        return sLibVLC != null || VLCUtil.hasCompatibleCPU(context);
    }
}
