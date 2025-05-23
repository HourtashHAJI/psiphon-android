/*
 * Copyright (c) 2022, Psiphon Inc.
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.psiphon3.psiphonlibrary;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.core.app.JobIntentService;

import com.psiphon3.R;
import com.psiphon3.log.MyLog;

import net.grandcentrix.tray.AppPreferences;

import java.io.File;
import java.util.Locale;

import ca.psiphon.PsiphonTunnel;

/*
 * Self-upgrading notes.
 * - UpgradeChecker is responsible for processing downloaded upgrade files (authenticate package,
 *   check APK version -- via UpgradeManager), notifying users of upgrades, and invoking the OS installer. Only
 *   UpgradeChecker will do these things, so we ensure there’s only one upgrade notification, etc.
 * - Every X hours, an alarm will wake up UpgradeChecker and it will launch its own tunnel-core and
 *   download an upgrade if no upgrade is pending. This achieves the Google Play-like
 *   upgrade-when-not-running.
 * - The Psiphon app tunnel-core will also download upgrades, if no upgrade is pending. It will make
 *   an untunneled check when it can’t connect. Or it will download when handshake indicates an
 *   upgrade is available.
 * - An upgrade is pending if a valid upgrade has been downloaded and is awaiting install. Both
 *   tunnel-cores need to be configured to skip upgrades when there’s a pending upgrade. In fact,
 *   the UpgradeChecker tunnel-core need not be started at all in this case.
 * - When the Psiphon app tunnel-core downloads an upgrade, it notifies UpgradeChecker with an
 *   intent. UpgradeChecker takes ownership of the downloaded file and proceeds as if it downloaded
 *   the file.
 *     - Because the app tunnel-core and UpgradeChecker download to the same filename, there's a
 *       race condition to access the files -- partial download file, unverified file, verified file.
 *       We will rely on file locking and package verification to keep the files sane. There is a
 *       very tiny chance that a file could get deleted right before it's replaced, causing an error
 *       when the user clicks the notification, but very tiny.
 */

public class UpgradeChecker extends BroadcastReceiver {
    private static final int ALARM_INTENT_REQUEST_CODE = 0;
    private static final String ALARM_INTENT_ACTION = UpgradeChecker.class.getName()+":ALARM";
    private static final String CREATE_ALARM_INTENT_ACTION = UpgradeChecker.class.getName()+":CREATE_ALARM";

    public static final String UPGRADE_FILE_AVAILABLE_INTENT_ACTION = UpgradeChecker.class.getName()+":UPGRADE_AVAILABLE";
    public static final String UPGRADE_FILE_AVAILABLE_INTENT_EXTRA_FILENAME = UpgradeChecker.class.getName()+":UPGRADE_FILENAME";

    /**
     * Checks whether an upgrade check should be performed. False will be returned if there's already
     * an upgrade file downloaded.
     * May be called from any process or thread.
     * Side-effect: If an existing upgrade file is detected, the upgrade notification will be displayed.
     * Side-effect: Creates the UpgradeChecker alarm.
     * @param context the context
     * @return true if upgrade check is needed.
     */
    public static boolean upgradeCheckNeeded(Context context) {
        Context appContext = context.getApplicationContext();

        // The main process will call this when it tries to connect, so we will use this opportunity
        // to make sure our alarm is created.
        createAlarm(appContext);

        // Don't re-download the upgrade package when a verified upgrade file is
        // awaiting application by the user. A previous upgrade download will have
        // completed and have been extracted to this verified upgrade file.
        // Without this check, tunnel-core won't know that the upgrade is already
        // downloaded, as the file name differs from UpgradeDownloadFilename, and
        // so the entire upgrade will be re-downloaded on each tunnel connect until
        // the user actually applies the upgrade.
        // As a result of this check, a user that delays applying an upgrade until
        // after a subsequent upgrade is released will first apply a stale upgrade
        // and then download the next upgrade.
        // Note: depends on getAvailableCompleteUpgradeFile deleting VerifiedUpgradeFile
        // after upgrade is complete. Otherwise, no further upgrades would download.
        // TODO: implement version tracking for the verified upgrade file so that
        // we can proceed with downloading a newer upgrade when an outdated upgrade exists
        // on disk.

        if (!allowedToSelfUpgrade(context)) {
            MyLog.i("UpgradeChecker.upgradeCheckNeeded: install does not support upgrading");
            return false;
        }

        File downloadedUpgradeFile = new File(PsiphonTunnel.getDefaultUpgradeDownloadFilePath(appContext));

        if (UpgradeManager.UpgradeInstaller.upgradeFileAvailable(appContext, downloadedUpgradeFile)) {
            MyLog.i("UpgradeChecker.upgradeCheckNeeded: upgrade file already exists");
            // We know there's an upgrade file available, so send an intent about it.
            Intent intent = new Intent(appContext, UpgradeChecker.class);
            intent.setAction(UPGRADE_FILE_AVAILABLE_INTENT_ACTION);
            intent.putExtra(UPGRADE_FILE_AVAILABLE_INTENT_EXTRA_FILENAME, downloadedUpgradeFile.getName());
            appContext.sendBroadcast(intent);
            return false;
        }

        // Verify if 'Download upgrades on WiFi only' user preference is on
        // but current network is not WiFi
        final AppPreferences multiProcessPreferences = new AppPreferences(appContext);
        if (multiProcessPreferences.getBoolean(
                context.getString(R.string.downloadWifiOnlyPreference), PsiphonConstants.DOWNLOAD_WIFI_ONLY_PREFERENCE_DEFAULT) &&
                !Utils.isOnWiFi(appContext)) {
            MyLog.i("UpgradeChecker.upgradeCheckNeeded: not checking due to WiFi only user preference");
            return false;
        }

        MyLog.i("UpgradeChecker.upgradeCheckNeeded: upgrade check needed");

        return true;
    }

    /**
     * Checks if the current app installation is allowed to upgrade itself.
     * @param appContext The application context.
     * @return true if the app is allowed to self-upgrade, false otherwise.
     */
    private static boolean allowedToSelfUpgrade(Context appContext) {
        if (EmbeddedValues.UPGRADE_URLS_JSON.length() == "[]".length()) {
            // We don't know where to find an upgrade.
            return false;
        }
        else if (EmbeddedValues.IS_PLAY_STORE_BUILD) {
            // Play Store Build instances must not use custom auto-upgrade, as it's a ToS violation.
            return false;
        }

        return true;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        // This service runs as a separate process, so it needs to initialize embedded values
        EmbeddedValues.initialize(context);

        // Make sure the alarm is created, regardless of which intent we received.
        createAlarm(context.getApplicationContext());

        String action = intent.getAction();

        if (action.equals(ALARM_INTENT_ACTION)) {
            MyLog.i("UpgradeChecker.onReceive: ALARM_INTENT_ACTION received");
            if (!upgradeCheckNeeded(context)) {
                return;
            }
            checkForUpgrade(context);
        }
        else if (action.equals(UPGRADE_FILE_AVAILABLE_INTENT_ACTION)) {
            MyLog.i("UpgradeChecker.onReceive: UPGRADE_FILE_AVAILABLE_INTENT_ACTION received");
            // Create upgrade notification. User clicking the notification will trigger the install.
            String filename = intent.getStringExtra(UPGRADE_FILE_AVAILABLE_INTENT_EXTRA_FILENAME);
            UpgradeManager.UpgradeInstaller.notifyUpgrade(LocaleManager.getInstance(context).setLocale(context), filename);
        }
        else if (action.equals(Intent.ACTION_BOOT_COMPLETED)) {
            MyLog.i("UpgradeChecker.onReceive: ACTION_BOOT_COMPLETED received");
            // Pass. We created the alarm above, so nothing else to do (until the alarm runs).
        }
        else if (action.equals(CREATE_ALARM_INTENT_ACTION)) {
            MyLog.i("UpgradeChecker.onReceive: CREATE_ALARM_INTENT_ACTION received");
            // Pass. We created the alarm above, so nothing else to do (until the alarm runs).
        }
    }

    /**
     * Creates the periodic alarm used to check for updates. Can be called unconditionally; it
     * handles cases when the alarm is already created.
     * @param appContext The application context.
     */
    private static void createAlarm(Context appContext) {
        if (!allowedToSelfUpgrade(appContext)) {
            // Don't waste resources with an alarm if we can't possibly self-upgrade.
            MyLog.i("UpgradeChecker.createAlarm: build does not allow self-upgrading; not creating alarm");
            return;
        }

        Intent intent = new Intent(appContext, UpgradeChecker.class);
        intent.setAction(ALARM_INTENT_ACTION);

        boolean alarmExists = (PendingIntent.getBroadcast(
                appContext,
                ALARM_INTENT_REQUEST_CODE,
                intent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ?
                        PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE :
                        PendingIntent.FLAG_NO_CREATE) != null);

        if (alarmExists) {
            MyLog.i("UpgradeChecker.createAlarm: alarmExists; aborting");
            return;
        }

        MyLog.i("UpgradeChecker.createAlarm: creating alarm");

        PendingIntent alarmIntent = PendingIntent.getBroadcast(
                appContext,
                ALARM_INTENT_REQUEST_CODE,
                intent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ?
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE :
                        PendingIntent.FLAG_UPDATE_CURRENT);

        AlarmManager alarmMgr = (AlarmManager)appContext.getSystemService(Context.ALARM_SERVICE);
        alarmMgr.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                AlarmManager.INTERVAL_HALF_DAY,
                alarmIntent);
    }

    /**
     * Launches the upgrade checking service. Returns immediately.
     */
    private void checkForUpgrade(Context context) {
        MyLog.i("UpgradeChecker.checkForUpgrade: starting service for upgrade check");
        UpgradeCheckerService.enqueueWork(context);
    }


    /**
     * The service that does the upgrade checking, via tunnel-core.
     */
    public static class UpgradeCheckerService extends JobIntentService implements PsiphonTunnel.HostService {
        private static final int JOB_ID = 10029;
        /**
         * The tunnel-core instance.
         */
        private PsiphonTunnel mTunnel = PsiphonTunnel.newPsiphonTunnel(this);

        /**
         * Keep track if the upgrade check is already in progress
         */
        private boolean mUpgradeCheckInProgress = false;

        /**
         * Used to post back to stop the tunnel, to avoid locking the thread.
         */
        Handler mStopHandler = new Handler();

        /**
         * Used to keep track of whether we've already sent the intent indicating that the
         * upgrade is available.
         */
        private boolean mUpgradeDownloaded;

        /**
         * Convenience method for enqueuing work in to this service.
         */
        public static void enqueueWork(Context context) {
            enqueueWork(context, UpgradeCheckerService.class, JOB_ID, new Intent());
        }

        /**
         * Entry point for starting the upgrade service.
         * @param intent Intent passed to enqueueWork, ignored.
         */
        @Override
        protected void onHandleWork(@NonNull Intent intent) {
            MyLog.i("UpgradeCheckerService: check starting");

            if (mUpgradeCheckInProgress) {
                // A check is already in progress, log and return
                MyLog.i("UpgradeCheckerService: check already in progress");
                // Not calling shutDownTunnel() because we don't want to interfere with the currently running request.
                return;
            }

            mUpgradeDownloaded = false;
            mUpgradeCheckInProgress = true;

            Utils.initializeSecureRandom();

            try {
                mTunnel.startTunneling(TunnelManager.getServerEntries(this));
            } catch (PsiphonTunnel.Exception e) {
                MyLog.e("UpgradeCheckerService: start tunnel failed: " + e);
                // No need to call shutDownTunnel().
                mUpgradeCheckInProgress = false;
                stopSelf();
            }
        }

        /**
         * Called when tunnel-core upgrade processing is finished (one way or another).
         * May be called more than once.
         */
        protected void shutDownTunnel() {
            final Context context = this;
            mStopHandler.post(new Runnable() {
                @Override
                public void run() {
                    MyLog.i("UpgradeCheckerService: check done");
                    mTunnel.stop();
                }
            });
        }

        /*
         * PsiphonTunnel.HostService implementation
         */

        @Override
        public String getPsiphonConfig() {
            // Build a temporary tunnel config to use
            TunnelManager.Config tunnelManagerConfig = new TunnelManager.Config();
            final AppPreferences multiProcessPreferences = new AppPreferences(this);
            tunnelManagerConfig.disableTimeouts = multiProcessPreferences.getBoolean(
                    this.getString(R.string.disableTimeoutsPreference), false);

            TunnelManager.setPlatformAffixes(mTunnel, "Psiphon_UpgradeChecker_");

            String tunnelCoreConfig = TunnelManager.buildTunnelCoreConfig(
                    this,                       // context
                    tunnelManagerConfig,
                    true,
                    "upgradechecker");           // tempTunnelName
            return tunnelCoreConfig == null ? "" : tunnelCoreConfig;
        }

        /**
         * Called when the tunnel discovers that we're already on the latest version. This indicates
         * that we can start shutting down.
         */
        @Override
        public void onClientIsLatestVersion() {
            MyLog.i("UpgradeCheckerService: client is latest version");
            shutDownTunnel();
        }

        /**
         * Called when the tunnel discovers that an upgrade has been downloaded. This indicates that
         * we should send an intent about it and start shutting down.
         */
        @Override
        public void onClientUpgradeDownloaded(String filename) {
            MyLog.i("UpgradeCheckerService: client upgrade downloaded");

            if (mUpgradeDownloaded) {
                // Because tunnel-core may create multiple server connections and do multiple
                // handshakes, onClientUpgradeDownloaded may get called multiple times.
                // We want to avoid sending the intent each time.
                return;
            }
            mUpgradeDownloaded = true;

            Intent intent = new Intent(this, UpgradeChecker.class);
            intent.setAction(UPGRADE_FILE_AVAILABLE_INTENT_ACTION);
            intent.putExtra(UPGRADE_FILE_AVAILABLE_INTENT_EXTRA_FILENAME, filename);
            this.sendBroadcast(intent);

            shutDownTunnel();
        }

        /**
         * Called when the tunnel has finished shutting down. We're all done and can shut down the JobIntentService
         * May be due to a connection timeout, or simply an exit triggered by one of the shutDownTunnel() calls.
         */
        @Override
        public void onExiting() {
            MyLog.i("UpgradeCheckerService: tunnel exiting");
            stopSelf();
        }

        @Override
        public void onDiagnosticMessage(String message) {
            MyLog.i(String.format(Locale.US, "UpgradeCheckerService: tunnel diagnostic: %s", message));
        }

        @Override
        public Context getContext() {
            return this;
        }
    }
}
