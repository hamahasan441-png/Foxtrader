package com.foxtrader.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * FoxTrader application entry point.
 * [HiltAndroidApp] triggers Hilt's code generation and creates the
 * application-level dependency container.
 */
@HiltAndroidApp
class FoxTraderApp : Application()
