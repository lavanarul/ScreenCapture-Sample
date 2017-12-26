package com.xpressit.screenrecorder.Activities;

import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.support.v7.app.AppCompatActivity;

import java.util.concurrent.TimeUnit;

/**
 * Created by Lavanya on 26-12-2017.
 */

public class SplashActivity extends AppCompatActivity {

    private final int timedelay = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        SystemClock.sleep(TimeUnit.SECONDS.toMillis(this.timedelay));
        finish();
    }
}
