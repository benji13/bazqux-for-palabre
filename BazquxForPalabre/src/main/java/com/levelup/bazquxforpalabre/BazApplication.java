package com.levelup.bazquxforpalabre;

import android.app.Application;
import com.crashlytics.android.Crashlytics;
import com.levelup.bazquxforpalabre.BuildConfig;

import io.fabric.sdk.android.Fabric;

/**
 * Created by nicolas on 23/07/15.
 */
public class BazApplication extends Application{

    @Override
    public void onCreate() {
        super.onCreate();
        if (BuildConfig.USE_CRASHLYTICS) {
            Fabric.with(this, new Crashlytics());
        }
    }
}
