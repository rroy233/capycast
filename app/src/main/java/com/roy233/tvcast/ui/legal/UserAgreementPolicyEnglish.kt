package com.roy233.tvcast.ui.legal

/** English translation of the versioned agreement. */
internal val userAgreementPolicyEnglish =
    """
    Open-source repository: https://github.com/rroy233/capycast

    ## 1. Nature of the software and open-source license
    CapyCast is a free, open-source Bilibili casting player for Android TV.

    CapyCast source code is released under the MIT License. Subject to the LICENSE file in the repository, you may use, copy, modify, merge, publish, distribute, sublicense, or sell copies of the software. When redistributing the software or substantial portions of it, you must retain the original copyright and MIT License notices.

    This agreement grants no rights to the names, trademarks, services, content, or interfaces of Bilibili, Google, Firebase, or other third parties. CapyCast has no affiliation, agency, partnership, or official authorization relationship with Bilibili or Google/Firebase unless expressly stated otherwise in writing.

    ## 2. Rules of use
    CapyCast provides client-side casting assistance. Video, live streams, danmaku, account services, membership benefits, and other content services are the responsibility of their respective rights holders or service providers. You must ensure that you are authorized to access the content and use the account, and must not use CapyCast to circumvent payment, geographic, copyright, account, or other access restrictions.

    You must comply with applicable laws and regulations, Bilibili's rules, and this agreement. You must not use CapyCast to infringe rights, break the law, compromise network security, or violate others' lawful interests.

    ## 3. Bilibili services and local data
    To provide QR-code login, playback resource resolution, danmaku, casting, and playback history synchronization when you enable it, the CapyCast client communicates directly with Bilibili services. Project maintainers do not operate relay, proxy, or data storage servers and do not receive, store, or forward this communication data.

    When you log in to Bilibili, the app stores necessary login credentials locally on your device, including account identifiers, access tokens, refresh tokens, and cookies, to maintain your session, request playback resources, and use related features. These local credentials are deleted when you log out or clear app data.

    When you enable “Remember Cast Content,” the app stores recent casting records locally, including content identifiers, titles, covers, quality, content type, and time, to display recent casts and allow casting again. These local records are deleted when you disable this option, clear app data, or uninstall the app.

    When you enable “Sync History to Bilibili,” the app sends Bilibili the content identifiers, playback progress, duration, playback status, and credentials needed for synchronization. Records that have not been sent successfully are stored temporarily on the device. Pending records are deleted when you disable synchronization, clear app data, or uninstall the app.

    Bilibili's processing of personal information in its services is governed by its own privacy policy: https://www.bilibili.com/blackboard/privacy-policy.html

    ## 4. Firebase data processing
    The app integrates Google Firebase services for quality maintenance: crash reporting (Firebase Crashlytics, including crash stacks, device model, and system version) and anonymized usage statistics (Firebase Analytics, such as daily active usage, page views, basic feature usage events, app version, and language and region). These do not include personal identifiers such as your account ID or phone number. This data is sent to Google's Firebase servers. You can independently disable the corresponding collection using “Crash Tracking” and “Usage Statistics” in the app settings.

    When “Crash Tracking” is enabled, Firebase Crashlytics may process crash stacks, related app state, device model, system version, app version, the Crashlytics installation identifier, and the Firebase installation identifier to diagnose crashes and stability issues.

    When “Usage Statistics” is enabled, Firebase Analytics may process app sessions, page views, basic feature usage events, app version, device model, system version, language and region, and the app instance identifier to measure usage and improve the product.

    We do not write your Bilibili account ID, phone number, login credentials, cookies, media titles, media IDs, search terms, playback URLs, or raw exception text to Firebase.

    Firebase is provided by Google, and relevant data may be transferred to or processed outside mainland China. You can independently disable “Crash Tracking” or “Usage Statistics” under Settings — Privacy and Data; the app stops the corresponding subsequent collection when disabled. Data already sent to Firebase is retained and deleted by Firebase under its applicable rules.

    ## 5. Information retention and management
    The app processes information only to the minimum extent necessary for the functions described in this agreement. Local information is stored in the Android app sandbox: credentials until logout or clearing app data; recent cast records until “Remember Cast Content” is disabled, app data is cleared, or the app is uninstalled; pending playback history until Bilibili confirms receipt, synchronization is disabled, app data is cleared, or the app is uninstalled; and app settings until you change them, clear app data, or uninstall the app.

    We do not sell your personal information or use it for advertising, user profiling, or marketing.

    In the app settings you can log out of your Bilibili account, disable remembering cast content, disable history synchronization, and independently disable the two Firebase collection options. You can also clear CapyCast app data or uninstall the app through Android settings to remove locally stored information.

    To access, correct, or delete personal information, withdraw consent, or make a complaint, comment, or suggestion about personal information processing, contact us through the open-source repository above. After verifying your request, we will handle and respond to it in accordance with applicable laws and regulations.

    ## 6. Protection of minors
    If you are under fourteen, use CapyCast with your guardian's consent and guidance. If a guardian believes that a minor has provided personal information to the app, they may contact the project maintainers through the repository above.

    ## 7. Disclaimer and limitation of liability
    To the maximum extent permitted by applicable law, CapyCast is provided “as is” and “as available.” Project maintainers make no express or implied warranty that the software will remain available, be defect-free or uninterrupted, be fully compatible with a particular device, or meet your specific needs.

    To the extent permitted by law, project maintainers are not liable for losses caused by network failures, device problems, changes to third-party services, Bilibili service restrictions, force majeure, or other causes outside their control. This clause does not exclude or limit liability that cannot be excluded or limited under applicable law, or release maintainers from liability for intentional misconduct or gross negligence as required by law.

    ## 8. Agreement updates and dispute resolution
    We may update this agreement when features, data processing, or laws and regulations change. Updates will be published in the app or the open-source repository. Where important changes involve the purposes, methods, categories, or recipients of personal information processing, we will obtain your consent again as required by law.

    The formation, validity, interpretation, performance, and dispute resolution of this agreement are governed by the laws of the People's Republic of China. In the event of a dispute, the parties should first negotiate amicably. If negotiations fail, either party may bring proceedings before a competent people's court according to law.
    """
        .trimIndent()
