package com.cawcafr.ameditor.util

/**
 * Contient la base de données des signatures de traqueurs et la logique de correspondance.
 */
object TrackersList {

    /**
     * Vérifie si le nom d'un composant correspond à un traqueur connu.
     */
    fun isTracker(name: String): Boolean {
        if (name.isEmpty()) return false
        // On vérifie si le nom contient l'un des patterns définis ci-dessous
        return PATTERNS.any { pattern ->
            name.contains(pattern, ignoreCase = true)
        }
    }
    /**
     * Ces composants ne doivent PAS être supprimés (sinon crash),
     * mais doivent être désactivés (android:enabled="false").
     */
    val COMPONENTS_TO_DISABLE = listOf(
        "com.onesignal.core.activities.PermissionsActivity",
        "com.onesignal.core.services.SyncJobService"
    )
    /**
     * Liste des permissions à supprimer (Strictement basées sur le regex).
     */
    val PERMISSIONS_TO_REMOVE = listOf(
        "com.google.android.gms.permission.AD_ID",
        "android.permission.AD_ID",
        "android.permission.AD_SERVICES_CONFIG",
        "android.permission.ACCESS_ADSERVICES_AD_ID",
        "android.permission.ACCESS_ADSERVICES_ATTRIBUTION",
        "android.permission.ACCESS_ADSERVICES_TOPICS"
    )

    /**
     * Liste massive fusionnée (Regex + Paquets connus).
     */
    private val PATTERNS = listOf(
        // --- GOOGLE & FIREBASE ---
        "com.google.android.gms.ads",
        "com.google.android.gms.analytics",
        "com.google.android.gms.measurement",
        "com.google.android.gms.tagmanager",
        "com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE",
        "google.android.datatransport",
        "com.google.firebase.analytics",
        "com.google.firebase.crashlytics",
        "io.invertase.firebase",
        "android.billingclient",
        "com.android.vending.billing",
        "android.adservices",
        "google.android.gms.ads.mediationtestsuite",

        // --- META / FACEBOOK ---
        "com.facebook.ads",
        "com.facebook.sdk.appevents",
        "com.facebook.sdk.advertiserid",
        "facebook.ads.AudienceNetworkContentProvider",

        // --- AMAZON ---
        "amazon.device.ads",
        "amazon.aps.ads",
        "amazon.client.metrics",
        "amazonaws",

        // --- HUAWEI ---
        "huawei.hms",
        "huawei.agconnect",
        "huawei.appmarket.service",
        "huawei.hff",
        "huawei.hwid",

        // --- ANALYTICS / CRASH / ATTRIBUTION ---
        "com.appsflyer",
        "com.adjust.sdk",
        "io.sentry",
        "com.bugsnag",
        "com.instabug",
        "org.acra",
        "ch.tutti.acra",
        "com.flurry",
        "com.mixpanel",
        "com.amplitude",
        "com.segment",
        "com.contentsquare",
        "com.clevertap",
        "com.moengage",
        "com.braze",
        "com.urbanairship",
        "cn.thinkingdata.analytics",
        "gameanalytics",
        "com.uxcam",
        "com.appsee",
        "io.appmetrica",
        "yandex.metrica",
        "com.sensorsdata",
        "cn.jpush",
        "heytap.msp",
        "wandoujia.zendesk",
        "com.appbrain",

        // --- ONESIGNAL (Ciblage précis) ---
        "onesignal.notificationDismissReceiver",
        "onesignal.notificationOpenedReceiver",
        "onesignal.FCMBroadcastReceiver",
        "onesignal.HmsMessageServiceOneSignal",
        "onesignal.FCMIntentService",
        "onesignal.NotificationOpenedActivity",
        "onesignal.UpgradeReceiver",
        "onesignal.BootUpReceiver",
        "onesignal.BadgeCount",
        "onesignal.notifications.services",
        "onesignal.notifications.receivers",
        "onesignal.core.receivers",
        "onesignal.core.services",
        "onesignal.core.activities",

        // --- AD NETWORKS (Réseaux Pub) ---
        "com.applovin",
        "com.mopub",
        "com.ironsource",
        "com.unity3d.ads",
        "com.unity3d.services.ads",
        "com.vungle",
        "com.adcolony",
        "com.chartboost",
        "com.startapp",
        "com.inmobi",
        "com.tapjoy",
        "com.fyber",
        "com.ogury",
        "com.onesignal",
        "io.presage",
        "com.criteo",
        "com.pubmatic",
        "net.pubnative",
        "com.appnext",
        "com.appodeal",
        "com.smaato",
        "com.smartadserver",
        "com.yoc.visx",
        "io.bidmachine",
        "com.tradplus",
        "com.anythink",
        "com.adxcorp",
        "com.mbridge.msdk",
        "com.mintegral",
        "com.bytedance.sdk", // Pangle / TikTok
        "com.pangle",
        "yandex.mobile.ads",
        "my.target",
        "my.tracker",
        "com.mytarget",
        "ru.vk.api.sdk",
        "tv.superawesome.sdk",
        "tv.teads.sdk",
        "com.mobilefuse",
        "com.wortise.ads",
        "com.loopme",
        "io.didomi.sdk",
        "com.adadapted",
        "com.explorestack",
        "sg.bigo.ads",
        "com.helpshift",
        "com.anzu.sdk",
        "com.hyprmx",
        "com.tappx",
        "com.gomfactory.adpie",
        "com.igaworks",
        "com.kakao.adfit",
        "com.mobon.sdk",
        "com.admixer",
        "com.coupang.ads",
        "tech.crackle",
        "co.notix",
        "com.five_corp.ad",
        "com.admost.sdk",
        "net.nend",
        "com.mngads",
        "org.prebid",
        "com.adswizz",
        "com.feedad",
        "com.rtb.sdk",
        "com.miui.systemAdSolution",
        "com.sina.weibo.sdk",
        "com.moloco.sdk",
        "tp.adx.sdk",
        "tutelatechnologies",
        "jio.jioads",
        "braintreepayments",
        "io.adjoe.sdk",

        // --- FLAGS (Meta-Data keys) ---
        "google_analytics_",
        "firebase_performance_",
        "firebase_analytics_",
        "firebase_crashlytics_",
        "firebase_crash_collection",
        "app_measurement_",
        "app_data_collection",
        "auto_data_collection",
        "analytics_collection",
        "ad_personalization",
        "ad_storage",
        "user_data_policy",
        "advertiser_id_collection",
        "delivery_metrics_exported"
    )
}