/*
 * Copyright (C) 2015 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.digits.sdk.android;

import android.annotation.TargetApi;
import android.os.Build;

import com.twitter.sdk.android.core.PersistedSessionManager;
import com.twitter.sdk.android.core.Session;
import com.twitter.sdk.android.core.SessionManager;
import com.twitter.sdk.android.core.TwitterAuthConfig;
import com.twitter.sdk.android.core.TwitterCore;
import com.twitter.sdk.android.core.internal.MigrationHelper;
import com.twitter.sdk.android.core.internal.SessionMonitor;
import com.twitter.sdk.android.core.internal.scribe.DefaultScribeClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

import io.fabric.sdk.android.Fabric;
import io.fabric.sdk.android.Kit;
import io.fabric.sdk.android.services.common.IdManager;
import io.fabric.sdk.android.services.concurrency.DependsOn;
import io.fabric.sdk.android.services.persistence.PreferenceStoreImpl;

/**
 * Digits allows authentication based on a phone number.
 */
@DependsOn(TwitterCore.class)
public class Digits extends Kit<Void> {
    public static final String TAG = "Digits";

    static final String PREF_KEY_ACTIVE_SESSION = "active_session";
    static final String PREF_KEY_SESSION = "session";
    static final String SESSION_PREF_FILE_NAME = "session_store";

    private final DigitsScribeClient digitsScribeClient;
    private final DigitsEventCollector digitsEventCollector;
    private volatile DigitsClient digitsClient;
    private volatile ContactsClient contactsClient;
    private SessionManager<DigitsSession> sessionManager;
    private SessionMonitor<DigitsSession> userSessionMonitor;
    private ActivityClassManager activityClassManager;
    private DefaultScribeClient twitterScribeClient;
    private DigitsSessionVerifier digitsSessionVerifier;

    private int themeResId;

    public Digits() {
        super();
        //create api client wrappers only.
        //all expensive api clients are created in the background
        digitsScribeClient = new DigitsScribeClient();
        digitsEventCollector = new DigitsEventCollector(digitsScribeClient);
    }

    public static Digits getInstance() {
        return Fabric.getKit(Digits.class);
    }

    /**
     * Starts the authentication flow
     *
     * @param callback {@link AuthCallback} to be called with the authentication result, or <code>null</code> if no callback is needed.
     *                 Digits holds a weak reference to this object, therefore the caller should
     *                 <strong>have a strong reference to this object</strong> or it will never receive
     *                 the result.
     * @deprecated replaced by {@link #authenticate(DigitsAuthConfig)} method
     */
    @Deprecated
    @SuppressWarnings("UnusedDeclaration")
    public static void authenticate(AuthCallback callback) {
        authenticate(callback, ThemeUtils.DEFAULT_THEME);
    }

    /**
     * Starts the authentication flow with the provided phone number.
     *
     * @param callback    {@link AuthCallback} to be called with the authentication result, or <code>null</code> if no callback is needed.
     *                    Digits holds a weak reference to this object, therefore the caller should
     *                    <strong>have a strong reference to this object</strong> or it will never receive
     *                    the result.
     * @param phoneNumber the phone number to authenticate
     * @deprecated replaced by {@link #authenticate(DigitsAuthConfig)} method
     */
    @Deprecated
    @SuppressWarnings("UnusedDeclaration")
    public static void authenticate(AuthCallback callback, String phoneNumber) {
        final DigitsAuthConfig.Builder digitsAuthConfigBuilder = new DigitsAuthConfig.Builder()
                .withAuthCallBack(callback)
                .withThemeResId(ThemeUtils.DEFAULT_THEME)
                .withPhoneNumber(phoneNumber);

        authenticate(digitsAuthConfigBuilder.build());
    }

    /**
     * Starts and sets the theme for the authentication flow.
     *
     * @param callback   will get the success or failure callback. It can be null,
     *                   but the developer will not get any callback.
     * @param themeResId Theme resource id
     * @deprecated replaced by {@link #authenticate(DigitsAuthConfig)} method
     */
    @Deprecated
    @SuppressWarnings("UnusedDeclaration")
    public static void authenticate(AuthCallback callback, int themeResId) {
        final DigitsAuthConfig.Builder digitsAuthConfigBuilder = new DigitsAuthConfig.Builder()
                .withAuthCallBack(callback)
                .withThemeResId(themeResId);

        authenticate(digitsAuthConfigBuilder.build());
    }

    /**
     * Starts the authentication flow with the provided phone number and theme.
     *
     * @param callback    will get the success or failure callback. It can be null,
     *                    but the developer will not get any callback.
     * @param themeResId  Theme resource id
     * @param phoneNumber the phone number to authenticate
     * @deprecated replaced by {@link #authenticate(DigitsAuthConfig)} method
     */
    @Deprecated
    @SuppressWarnings("UnusedDeclaration")
    public static void authenticate(AuthCallback callback, int themeResId, String phoneNumber,
                                    boolean emailCollection) {
        final DigitsAuthConfig.Builder digitsAuthConfigBuilder = new DigitsAuthConfig.Builder()
                .withAuthCallBack(callback)
                .withThemeResId(themeResId)
                .withPhoneNumber(phoneNumber)
                .withEmailCollection(emailCollection);

        authenticate(digitsAuthConfigBuilder.build());
    }

    @SuppressWarnings("UnusedDeclaration")
    public static void authenticate(DigitsAuthConfig digitsAuthConfig) {
        getInstance().setTheme(digitsAuthConfig.themeResId);
        getInstance().setExternalLogger(digitsAuthConfig.digitsEventLogger);
        getInstance().getDigitsClient().startSignUp(digitsAuthConfig);
    }

    public static SessionManager<DigitsSession> getSessionManager() {
        return getInstance().sessionManager;
    }

    /**
     * Adds a {@link SessionListener} to the list of notifiers when the session changes.
     * <p>
     * Internally a strong reference is held to this sessionListener. In case this
     * sessionListener is instantiated inside an Activity context, when the Activity is being
     * destroyed, the sessionListener must be remove from the list {@link #removeSessionListener}
     *
     * @param sessionListener element to add to the list of notifiers.
     */
    public void addSessionListener(SessionListener sessionListener) {
        if (sessionListener == null) {
            throw new NullPointerException("sessionListener must not be null");
        }
        digitsSessionVerifier.addSessionListener(sessionListener);
    }

    /**
     * Removes the {@link SessionListener} from the list of notifiers.
     *
     * @param sessionListener element to be removed
     */
    public void removeSessionListener(SessionListener sessionListener) {
        if (sessionListener == null) {
            throw new NullPointerException("sessionListener must not be null");
        }
        digitsSessionVerifier.removeSessionListener(sessionListener);
    }

    @Override
    public String getVersion() {
        return BuildConfig.VERSION_NAME + "." + BuildConfig.BUILD_NUMBER;
    }

    @Override
    protected boolean onPreExecute() {
        final MigrationHelper migrationHelper = new MigrationHelper();
        migrationHelper.migrateSessionStore(getContext(), getIdentifier(),
                getIdentifier() + ":" + SESSION_PREF_FILE_NAME + ".xml");
        sessionManager = new PersistedSessionManager<>(new PreferenceStoreImpl(getContext(),
                SESSION_PREF_FILE_NAME), new DigitsSession.Serializer(), PREF_KEY_ACTIVE_SESSION,
                PREF_KEY_SESSION);
        digitsSessionVerifier = new DigitsSessionVerifier();
        return super.onPreExecute();
    }

    @Override
    protected Void doInBackground() {
        // Trigger restoration of session
        sessionManager.getActiveSession();
        createTwitterScribeClient(sessionManager, getIdManager());
        digitsScribeClient.setTwitterScribeClient(twitterScribeClient);
        createDigitsClient();
        createContactsClient();
        userSessionMonitor = new SessionMonitor<>(getSessionManager(), getExecutorService(),
                digitsSessionVerifier);
        // Monitor activity lifecycle after sessions have been restored. Otherwise we would not
        // have any sessions to monitor anyways.
        userSessionMonitor.monitorActivityLifecycle(getFabric().getActivityLifecycleManager());
        return null;
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    int getTheme() {
        if (themeResId != ThemeUtils.DEFAULT_THEME) {
            return themeResId;
        }

        return R.style.Digits_default;
    }

    protected void setTheme(int themeResId) {
        this.themeResId = themeResId;
        createActivityClassManager();
    }

    @Override
    public String getIdentifier() {
        return BuildConfig.GROUP + ":" + BuildConfig.ARTIFACT_ID;
    }

    DigitsClient getDigitsClient() {
        if (digitsClient == null) {
            createDigitsClient();
        }
        return digitsClient;
    }

    protected DigitsEventCollector getDigitsEventCollector() {
        return digitsEventCollector;
    }

    private synchronized void createDigitsClient() {
        if (digitsClient == null) {
            digitsClient = new DigitsClient();
        }
    }

    public ContactsClient getContactsClient() {
        if (contactsClient == null) {
            createContactsClient();
        }
        return contactsClient;
    }

    private synchronized void createContactsClient() {
        if (contactsClient == null) {
            contactsClient = new ContactsClient();
        }
    }

    protected ExecutorService getExecutorService() {
        return getFabric().getExecutorService();
    }

    private synchronized void createTwitterScribeClient(SessionManager sessionManager,
                                                        IdManager idManager) {
        if (twitterScribeClient == null) {
            final DigitsUserAgent userAgent = new DigitsUserAgent();
            final List<SessionManager<? extends Session>> sessionManagers = new ArrayList<>();
            sessionManagers.add(sessionManager);

            twitterScribeClient = new DefaultScribeClient(this, userAgent.toString(),
                    sessionManagers, idManager);
        }
    }

    protected ActivityClassManager getActivityClassManager() {
        if (activityClassManager == null) {
            createActivityClassManager();
        }
        return activityClassManager;
    }

    protected void createActivityClassManager() {
        final ActivityClassManagerFactory factory = new ActivityClassManagerFactory();
        activityClassManager = factory.createActivityClassManager(getContext(), themeResId);
    }

    /**
     * Exposes the AuthConfig used in this instance of Digits kit
     */
    @SuppressWarnings("UnusedDeclaration")
    public TwitterAuthConfig getAuthConfig() {
        return TwitterCore.getInstance().getAuthConfig();
    }

    private void setExternalLogger(DigitsEventLogger externalEventLogger) {
        digitsEventCollector.setLoggerResultReceiver(externalEventLogger);
    }
}
