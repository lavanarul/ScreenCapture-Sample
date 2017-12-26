

package com.xpressit.screenrecorder.Listener;
/**
 * Created by Lavanya on 26-12-2017.
 */
public interface PermissionResultListener {
    void onPermissionResult(int requestCode,
                            String permissions[], int[] grantResults);
}
