<img src="https://r2cdn.perplexity.ai/pplx-full-logo-primary-dark%402x.png" style="height:64px;margin-right:32px"/>

# HyperOS убивает фоновое приложение ровно через 6 часов: что происходит и как обойти

## Почему именно 6 часов

С высокой вероятностью это не “рандомная” агрессия HyperOS, а следствие нового поведения Android 15 для foreground-сервисов типа dataSync: система разрешает суммарно до 6 часов работы за 24 часа, после чего вызывает onTimeout и, если сервис сам не остановится, генерирует внутреннее исключение (RemoteServiceException) — это выглядит как «вылет с ошибкой» ровно по таймеру[^1_1]. Если приложение таргетит Android 15 (API 35) или устройство на HyperOS внедрило это поведение системно, вы получите именно такой эффект[^1_1].

Ключевые детали механизма:

- Лимит: 6 часов суммарной работы foreground-сервиса типа dataSync за 24 часа[^1_1].
- По истечении — вызов Service.onTimeout(), затем несколько секунд, чтобы вызвать stopSelf(); если не успеть — падение[^1_1].
- Если лимит исчерпан, повторный старт dataSync-FGS невозможен без вывода приложения на передний план пользователем (таймер сбрасывается при взаимодействии)[^1_1].

Это объясняет, почему на crDroid всё стабильно (там может не быть этого ограничения/другая реализация), а на HyperOS воспроизводится строго через 6 часов[^1_1].

## Почему «антикилл»‑настройки не помогают

Классические меры в MIUI/HyperOS — “Без ограничений” в экономии батареи, автозапуск, отключение «при бездействии отзывать разрешения», закрепление в многозадачности, Lock Apps в «Безопасность» — помогают против обычного убийства фона, но не против системного тайм‑аута foreground-сервиса Android 15[^1_2][^1_3][^1_4][^1_5]. Это ограничение выше уровнем и триггерится по времени работы сервиса, а не по политике энергосбережения[^1_1].

## Что проверить в логах/коде

- Логи на момент падения: ищите RemoteServiceException с текстом вроде “A foreground service of type dataSync did not stop within its timeout”[^1_1].
- Тип FGS: если вы используете foreground service для DOM‑мониторинга и указали type="dataSync" (или аналогично), вы попадаете под 6‑часовой лимит[^1_1].
- Target SDK: при targetSdkVersion 35 поведение становится обязательным; на более низких SDK часть ограничений может применяться производителем[^1_1].


## Варианты обхода и исправления

Приоритет — перестроить архитектуру под новые правила Android 15.

1) Реализовать корректное завершение по тайм‑ауту

- Обработать Service.onTimeout(int,int) и немедленно вызывать stopSelf(), чтобы избежать крэша[^1_1].
- Планировать возобновление работы без FGS до взаимодействия пользователя или при следующем допустимом окне[^1_1].

2) Не использовать dataSync‑FGS для длительной фоновой работы

- По возможности уйти от постоянного foreground-сервиса к WorkManager/JobScheduler с гибкими триггерами и ограничениями, чтобы не держать FGS часами[^1_1].
- Если нужен FGS — запускать его только по прямому действию пользователя, что даст «окно» до 6 часов после ухода в фон, и строго укладываться в лимит[^1_1].

3) Сменить тип/модель выполнения

- Оценить, нужен ли именно FGS: для мониторинга DOM на сайте лучше организовать опрос сервером и пушить события на устройство (push/FCM), а на телефоне держать обычный процесс/WorkManager, а не длительный FGS[^1_1].
- Если без FGS никак, убедиться, что тип сервиса выбран корректно и не попадает под самый строгий лимит; однако в Android 15 ограничения расширены и для mediaProcessing, так что злоупотреблять типами не стоит[^1_1].

4) Сброс лимита только через взаимодействие пользователя

- После исчерпания 6 часов новый запуск dataSync‑FGS невозможен до вывода приложения на передний план пользователем; учитывайте это в UX (уведомление-акция для открытия приложения)[^1_1].

5) Системные настройки, которые все же стоит включить (не решают лимит, но исключают прочие убийства)

- Включить Autostart/Background autostart и No restrictions для батареи у конкретного приложения в HyperOS[^1_2][^1_5].
- Отключить “Pause app activity if unused” (Отзывать разрешения при неиспользовании)[^1_2][^1_3].
- Закрепить/заблокировать приложение через Security → Boost Speed → Lock Apps (если доступно)[^1_4].

Эти меры снизят «обычные» убийства фона на HyperOS, но 6‑часовой тайм‑аут Android 15 они не отменят[^1_2][^1_4][^1_5][^1_1].

## Почему у тестера на MIUI «не было 6 часов»

- Другая версия Android/MIUI без включённого нового поведения.
- Иной тип сервиса, меньше накопленное время, или пользователь периодически открывал приложение, тем самым сбрасывая лимит[^1_1].
- Иной targetSdkVersion у сборки[^1_1].


## Если очень нужно «как раньше»

- На кастомах вроде crDroid ограничения могли быть ослаблены — поэтому и работает[^1_1].
- На HyperOS попытки «отключить оптимизации MIUI», удалить com.miui.powerkeeper и пр. обсуждаются на форумах, но даже при этих ухищрениях пользователи отмечают, что система все равно добивает фон в ряде сценариев[^1_6]. Это не решает 6‑часовой лимит Android 15 для FGS[^1_1][^1_6].


## Резюме действий для разработчика

- Проверить логи на предмет onTimeout/RemoteServiceException ровно в момент 6ч[^1_1].
- Если используете FGS типа dataSync — перестроить логику:
    - имплементировать onTimeout() и stopSelf()[^1_1],
    - сократить суммарное время FGS до <6ч/24ч[^1_1],
    - запускать FGS только по действию пользователя[^1_1],
    - перенести длительную работу на WorkManager/сервер+push[^1_1].
- В настройках HyperOS для пользователя: Autostart/Background autostart=On, Battery=No restrictions, отключить «Pause app activity if unused», закрепить приложение в «Security»[^1_2][^1_4][^1_5][^1_3].

Это единственный надежный путь убрать «вылет ровно через 6 часов», потому что он вызван системным тайм‑аутом Android 15 для foreground‑сервисов, а не общей «агрессией» HyperOS к фону[^1_1].
<span style="display:none">[^1_10][^1_11][^1_12][^1_13][^1_14][^1_15][^1_16][^1_17][^1_18][^1_19][^1_20][^1_7][^1_8][^1_9]</span>

<div style="text-align: center">⁂</div>

[^1_1]: https://developer.android.com/about/versions/15/behavior-changes-15

[^1_2]: https://adguard.com/kb/ja/adguard-for-android/solving-problems/background-work/

[^1_3]: https://droidwin.com/miui-killing-background-apps-on-xiaomi-how-to-fix/

[^1_4]: https://www.reddit.com/r/miui/comments/1adx993/hyperos_forcibly_closing_background_app/

[^1_5]: https://dontkillmyapp.com/xiaomi

[^1_6]: https://xiaomi.eu/community/threads/apps-closes-killed-when-i-switch.71291/

[^1_7]: https://www.reddit.com/r/miui/comments/1hhn9ut/for_those_who_have_battery_drain_issue_in_hyperos/

[^1_8]: https://xdaforums.com/t/is-there-any-way-of-not-having-miui-terminate-background-apps-every-few-hours.4641583/

[^1_9]: https://www.allabouthyperos.com/2024/11/hyperos-how-to-prevent-apps.html

[^1_10]: https://www.youtube.com/watch?v=azSjmmVfadU

[^1_11]: https://www.youtube.com/watch?v=DdQ4kb7N1g4

[^1_12]: https://www.androidauthority.com/stop-android-background-apps-664842/

[^1_13]: https://appuals.com/xiaomi-phone-closes-background-apps/

[^1_14]: https://www.reddit.com/r/AndroidQuestions/comments/18qst65/how_to_suspend_background_process_in_android_14/

[^1_15]: https://xiaomi.eu/community/threads/miui-14-limit-background-activity-even-after-customization.69748/

[^1_16]: https://github.com/transistorsoft/flutter_background_geolocation/issues/1226

[^1_17]: https://xdaforums.com/t/is-there-a-way-to-auto-close-background-running-app-after-a-specific-time.4604919/

[^1_18]: https://forums.androidcentral.com/threads/force-stop.471413/

[^1_19]: https://stackoverflow.com/questions/49259130/background-service-stops-on-android-marshmallow-6-0-devices

[^1_20]: https://forum.earlybird.club/threads/apps-restarting-themselves-after-force-stop.352806/


---

## Что видно по присланным скринам логов

- В стеке фигурирует ForegroundServiceStartNotAllowedException с текстом “Time limit already exhausted for foreground service type dataSync”, а также RemoteServiceException при старте/поддержании foreground‑сервиса.[^2_1][^2_2]
- Это ровно описанное в Android 15 поведение: для foreground‑сервисов типа dataSync (и mediaProcessing) введён лимит — суммарно до 6 часов работы в фоне за 24 часа; по достижении лимита сервис переводится из foreground, вызывается onTimeout(), и если он не остановится за несколько секунд, система кидает исключение и «роняет» процесс.[^2_1][^2_2]
- После исчерпания лимита запуск нового dataSync‑FGS запрещён, пока пользователь не выведет приложение на передний план; при попытке старта система выбрасывает то самое ForegroundServiceStartNotAllowedException с сообщением про «Time limit already exhausted…» — именно это и видно в ваших логах.[^2_1][^2_2]
- Похожие отчёты есть у других проектов и на issue‑треках/обсуждениях, что подтверждает, что это системное изменение Android 15, а не частная ошибка конкретной прошивки.[^2_3][^2_4][^2_5]


## Что делать разработчику

- Обработать новый колбэк Service.onTimeout(int,int) и немедленно вызывать stopSelf(), чтобы избежать крэша при достижении лимита.[^2_1][^2_2]
- Перестроить выполнение длительных задач: не держать постоянный dataSync‑FGS, а переносить работу в WorkManager/JobScheduler, запускать FGS только по действию пользователя и укладываться в лимит 6ч/24ч.[^2_1][^2_2]
- Учитывать, что после исчерпания лимита повторный старт dataSync‑FGS возможен только после вывода приложения пользователем на передний план (это «сбрасывает» таймер).[^2_1][^2_2]


## Почему это «каждые 6 часов» именно на HyperOS

- На устройствах с Android 15 производители (в т.ч. HyperOS) применяют это поведение системы; на других прошивках или при ином targetSdk оно могло не активироваться, поэтому на crDroid у тестера всё работало.[^2_1][^2_2][^2_3]

Если нужны, могу подсказать, как конкретно переехать на WorkManager для вашего сценария мониторинга и где корректно вызывать startForeground/stopSelf с учётом onTimeout.[^2_2]
<span style="display:none">[^2_10][^2_11][^2_12][^2_13][^2_14][^2_15][^2_16][^2_17][^2_18][^2_19][^2_20][^2_21][^2_6][^2_7][^2_8][^2_9]</span>

<div style="text-align: center">⁂</div>

[^2_1]: https://developer.android.com/about/versions/15/behavior-changes-15

[^2_2]: https://developer.android.com/develop/background-work/services/fgs/timeout

[^2_3]: https://github.com/androidx/media/issues/2614

[^2_4]: https://github.com/home-assistant/android/issues/5338

[^2_5]: https://issuetracker.google.com/issues/364508145

[^2_6]: 1000827193.jpg

[^2_7]: 1000827191.jpg

[^2_8]: 1000827192.jpg

[^2_9]: https://github.com/Catfriend1/syncthing-android/issues/1147

[^2_10]: https://stackoverflow.com/questions/79010058/getting-foregroundservicestartnotallowedexception-when-starting-a-foreground-ser

[^2_11]: https://www.linkedin.com/posts/mangeshkokare_androiddev-android15-foregroundservices-activity-7237313714771673088-XX-Q

[^2_12]: https://www.linkedin.com/posts/dhaval-lathiya_important-update-for-android-developers-activity-7289122400124727296-oxtL

[^2_13]: https://stackoverflow.com/questions/76943771/foreground-service-crashing-on-android-14

[^2_14]: https://softices.com/blogs/android-foreground-services-types-permissions-use-cases-limitations

[^2_15]: https://github.com/seedvault-app/seedvault/issues/795

[^2_16]: https://habr.com/ru/articles/922100/

[^2_17]: https://fire-monkey.ru/topic/14327-foreground-service-под-андроид-14/

[^2_18]: https://discuss.grapheneos.org/d/21294-orbot-vs-proton-vpn-and-disconnects-from-both

[^2_19]: https://github.com/deltachat/deltachat-android/issues/3281

[^2_20]: https://issuetracker.google.com/issues/364508145/resources

[^2_21]: https://www.reddit.com/r/tasker/comments/1ctoboi/potential_future_problems_with_android_15_and/


---

# Как обеспечить непрерывную работу в фоне на Android 15, если сейчас «убивает» через 6ч

Проблема вызвана новым правилом Android 15: foreground‑сервисы типов dataSync и mediaProcessing могут работать суммарно не более 6 часов за 24 часа, после чего система вызывает Service.onTimeout() и требует остановить сервис; дальнейшие попытки старта выбросят ForegroundServiceStartNotAllowedException с текстом «Time limit already exhausted…»[^3_1][^3_2]. Это и приводит к падению ровно через 6ч на устройствах с Android 15/HyperOS[^3_1][^3_3].

## Что это означает на практике

- После исчерпания лимита 6ч новый dataSync/mediaProcessing FGS нельзя запустить, пока пользователь не выведет приложение на передний план (это «сбрасывает» таймер)[^3_1][^3_2].
- При тайм‑ауте сервис должен за несколько секунд вызвать stopSelf(), иначе система кинет RemoteServiceException и процесс упадёт[^3_1][^3_2].
- В логах это выглядит как ForegroundServiceStartNotAllowedException: Time limit already exhausted for foreground service type dataSync — именно это сообщение формирует фреймворк[^3_4][^3_1].


## Рабочие варианты решения

1) Убрать постоянный FGS и перейти на планировщики

- Перенести длительную фоновую работу в WorkManager/JobScheduler, запускать короткие задачи по условиям (сеть/заряд/периодичность), без постоянного foreground‑сервиса[^3_1][^3_2].
- Если требуется устойчивое соединение/прослушка, по возможности сместить логику на сервер и доставлять события пушами (FCM), чтобы устройство не держало FGS[^3_1][^3_2].

2) Если FGS всё же нужен — уложиться в правила

- Стартовать FGS только по явному действию пользователя; так сервис получит «окно» до 6ч после ухода приложения в фон[^3_1][^3_2].
- Сократить суммарное время работы dataSync/mediaProcessing до <6ч/24ч, разносить задачи, а остальное делать неблокирующими механизмами[^3_1][^3_2].
- Обработать Service.onTimeout(int, int): на колбэке сразу освобождать ресурсы и вызывать stopSelf(), чтобы избежать крэша[^3_1][^3_5].

3) Разграничить типы FGS

- Убедиться, что используется корректный тип foreground‑сервиса; лимит 6ч распространяется на dataSync и mediaProcessing, но не на все типы безусловно[^3_2][^3_6]. Злоупотреблять типами нельзя, однако если задача реально относится к другому типу (например, location/mediaPlayback и т.п.), применяйте соответствующий тип согласно правилам платформы[^3_2][^3_6].

4) Поведение после исчерпания лимита

- Когда лимит исчерпан, показать пользователю уведомление‑действие «Открыть приложение», чтобы вывести его на передний план и тем самым разрешить повторный запуск FGS[^3_1][^3_2].

5) Тестирование и диагностика

- Для воспроизведения и быстрой отладки можно временно уменьшить таймаут через adb device_config и проверить обработку onTimeout/stopSelf()[^3_1].
- В логах ориентироваться на RemoteServiceException и ForegroundServiceStartNotAllowedException с сообщениями о «Time limit already exhausted…»[^3_1][^3_3].


## Почему «антикилл» HyperOS не помогает

Настройки «Без ограничений», автозапуск и т.п. защищают от агрессивного убийства фона, но не отменяют системный лимит времени для FGS в Android 15 — это изменение уровня платформы, а не только прошивки[^3_1][^3_2]. Сообщество и проекты уже фиксируют аналогичные ограничения и падения при достижении лимита[^3_7][^3_3][^3_8].

## Итог

Чтобы приложение продолжало работать в фоне дольше 6ч на Android 15, нужно отказаться от постоянно работающего dataSync/mediaProcessing FGS или строго вписать его в лимит, переложив основную фоновую активность на WorkManager/пуши и корректно обрабатывая onTimeout() с последующим stopSelf()[^3_1][^3_2][^3_5].
<span style="display:none">[^3_10][^3_11][^3_12][^3_13][^3_14][^3_15][^3_16][^3_17][^3_18][^3_19][^3_20][^3_21][^3_9]</span>

<div style="text-align: center">⁂</div>

[^3_1]: https://developer.android.com/develop/background-work/services/fgs/timeout

[^3_2]: https://developer.android.com/about/versions/15/behavior-changes-15

[^3_3]: https://github.com/androidx/media/issues/2614

[^3_4]: https://android.googlesource.com/platform/frameworks/base.git/+/master/services/core/java/com/android/server/am/ActiveServices.java

[^3_5]: https://learn.microsoft.com/en-us/dotnet/api/android.app.service.ontimeout?view=net-android-35.0

[^3_6]: https://github.com/home-assistant/android/issues/5338

[^3_7]: https://github.com/Catfriend1/syncthing-android/issues/1147

[^3_8]: https://github.com/seedvault-app/seedvault/issues/795

[^3_9]: 1000827193.jpg

[^3_10]: 1000827191.jpg

[^3_11]: 1000827192.jpg

[^3_12]: https://www.linkedin.com/posts/mangeshkokare_androiddev-android15-foregroundservices-activity-7237313714771673088-XX-Q

[^3_13]: https://github.com/Catfriend1/syncthing-android/issues/1266

[^3_14]: https://www.linkedin.com/posts/pradip-radadiya-038a94b9_android-15-foreground-services-new-rules-activity-7298000886574858240-m8cp

[^3_15]: https://stackoverflow.com/questions/70044393/fatal-android-12-exception-startforegroundservice-not-allowed-due-to-mallows

[^3_16]: https://www.reddit.com/r/androiddev/comments/1csvfe7/android_15_beta_2_new_changes/

[^3_17]: https://stackoverflow.com/questions/76943771/foreground-service-crashing-on-android-14

[^3_18]: https://issuetracker.google.com/issues/364508145

[^3_19]: https://docs.omnissa.com/bundle/GettingReadyforAndroidReleasesVSaaS/page/AndroidGettingReady2024.html

[^3_20]: https://www.linkedin.com/posts/abu-sayed-chowdhury_foreground-service-timeouts-background-activity-7348784502355226624-vOeL

[^3_21]: https://discuss.grapheneos.org/d/21294-orbot-vs-proton-vpn-and-disconnects-from-both


---

# Как надёжно отслеживать важные сообщения на Android 15 при частой проверке

Важно принять во внимание системные ограничения Android 15: длительные foreground‑сервисы типа dataSync/mediaProcessing ограничены 6ч суммарно за 24ч и после исчерпания лимита больше не могут стартовать из фона без взаимодействия пользователя[^4_1][^4_2]. Поэтому стратегия «вечный FGS + периодический опрос каждые 10–300 секунд» на Android 15 обречена на остановку через 6ч, независимо от «Без ограничений» в батарее.

Ниже — архитектуры, которые позволяют держать опрос/уведомления надёжно и дольше 6ч, с разной частотой.

## Если допустима проверка ≥15 минут

- Перейти на WorkManager PeriodicWorkRequest с интервалом ≥15 минут; это системный минимум и он жёстко зафиксирован в API[^4_3][^4_4][^4_5].
- Для гибкости использовать flex-интервал, чтобы система запускала работу в «окно» и экономила батарею[^4_3][^4_4].
- FGS вообще не нужен: задача выполняется кратковременно раз в интервал, уведомления показываются обычным Notification. Это полностью совместимо с Android 15 и не упирается в 6‑часовой лимит[^4_2][^4_3].


## Если нужна частота <15 минут (ваш слайдер 10с–10мин)

Системно PeriodicWorkRequest не может быть чаще 15 минут[^4_5][^4_3]. Есть два рабочих пути:

1) Push‑модель вместо опроса

- Вынести «мониторинг DOM» на сервер: сервер отслеживает новые сообщения и отправляет FCM push при событии.
- На устройстве нет постоянного FGS и нет частого опроса — будит пуш, вы показываете уведомление и при необходимости коротко синхронизируетесь фоново. Это масштабируемо и устойчиво к лимиту 6ч[^4_2][^4_1].

2) «Короткие» будилки через точные будильники

- Для сценариев «пользователь‑критичные напоминания/сигнализации» можно использовать точные будильники AlarmManager.setExact()/setExactAndAllowWhileIdle, запросив одно из специальных разрешений: SCHEDULE_EXACT_ALARM (через экран «Будильники и напоминания») или USE_EXACT_ALARM в разрешённых кейсах[^4_6][^4_7][^4_8].
- Обязательные условия:
    - Обосновать кейс пользователю и запросить доступ через ACTION_REQUEST_SCHEDULE_EXACT_ALARM; на Android 14+ доступ по умолчанию отклонён и должен быть выдан вручную[^4_7][^4_6].
    - Будильники предназначены для точных, пользовательски‑значимых событий; Google Play ограничивает злоупотребление ими, поэтому использовать их нужно экономно, а не как «каждые 10 секунд» бесконечно[^4_7][^4_6][^4_8].
    - Реализовать одноразовые setExact с перепланированием на следующий интервал; выполнять задачу быстро и без постоянного FGS, чтобы не упереться в 6ч[^4_2][^4_6].

Практически: для частоты, скажем, 1–10 минут — разумно оценить, можно ли временно перейти на push‑модель или увеличить интервал до 15 минут под WorkManager; для критически малых интервалов — ограниченное применение exact alarms, но только если политику магазина и UX удаётся обосновать[^4_7][^4_6][^4_8].

## Если FGS всё же нужен

- Запускать FGS только по явному действию пользователя, укладываясь в 6ч «окна» после ухода в фон; обработать Service.onTimeout() и немедленно вызывать stopSelf(), иначе система уронит процесс[^4_2][^4_1].
- После исчерпания лимита новый dataSync‑FGS не стартует до вывода приложения на передний план пользователем — это поведение платформы[^4_1][^4_2].


## Что выбрать под ваш слайдер 10с–10мин

- Важные и редкие события: push‑модель через сервер+FCM — лучший баланс доставки/ресурса[^4_2].
- Частый опрос «по требованию» пользователя: краткий FGS, стартующий из активной UI‑сессии, затем работать не более 6ч и завершаться по onTimeout (для «дежурных смен», например)[^4_1][^4_2].
- Постоянный автономный мониторинг без сервера: либо поднять интервал до ≥15 минут и уйти на WorkManager, либо очень аккуратно применять exact alarms для интервалов <15 минут, понимая требования разрешения и политики[^4_3][^4_7][^4_6].

Таким образом, чтобы приложение надёжно отслеживало сообщения на Android 15 сутки напролёт, нужно отказаться от «вечного FGS с частым опросом» в пользу push или планировщиков, а для сверхчастых проверок — использовать точные будильники только по обоснованным пользовательским кейсам и без злоупотребления[^4_2][^4_1][^4_6].
<span style="display:none">[^4_10][^4_11][^4_12][^4_13][^4_14][^4_15][^4_16][^4_17][^4_18][^4_19][^4_20][^4_21][^4_22][^4_23][^4_9]</span>

<div style="text-align: center">⁂</div>

[^4_1]: https://developer.android.com/about/versions/15/behavior-changes-15

[^4_2]: https://developer.android.com/develop/background-work/services/fgs/timeout

[^4_3]: https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work

[^4_4]: https://android.googlesource.com/platform/frameworks/support/+/a1611a36e8debaa32dd82ef9830613e7ec29a8bb/work/workmanager/src/main/java/androidx/work/PeriodicWorkRequest.java

[^4_5]: https://stackoverflow.com/questions/55135999/how-to-reduce-time-of-periodicworkmanager-in-workmanager/55349427

[^4_6]: https://developer.android.com/develop/background-work/services/alarms/schedule

[^4_7]: https://developer.android.com/about/versions/14/changes/schedule-exact-alarms

[^4_8]: https://codewithninad.com/using-android-schedule-exact-alarm-permission-explained/

[^4_9]: 1000827193.jpg

[^4_10]: 1000827191.jpg

[^4_11]: 1000827192.jpg

[^4_12]: https://github.com/Catfriend1/syncthing-android/issues/1147

[^4_13]: https://github.com/Catfriend1/syncthing-android/issues/1266

[^4_14]: https://www.linkedin.com/posts/pradip-radadiya-038a94b9_android-15-foreground-services-new-rules-activity-7298000886574858240-m8cp

[^4_15]: https://stackoverflow.com/questions/79010058/getting-foregroundservicestartnotallowedexception-when-starting-a-foreground-ser

[^4_16]: https://www.tothenew.com/blog/using-work-managers-periodic-work-for-less-than-15-minutes/

[^4_17]: https://orangeoma.zendesk.com/hc/en-us/articles/9110068699548-Google-Play-policy-on-use-of-Exact-Alarm-API

[^4_18]: https://stackoverflow.com/questions/79600699/loading-assistant-content-appears-to-be-hung

[^4_19]: https://issuetracker.google.com/124274584

[^4_20]: https://issuetracker.google.com/issues/364508145

[^4_21]: https://stackoverflow.com/questions/72236143/ways-to-work-around-the-android-workmanager-15-minute-minimum-interval

[^4_22]: https://stackoverflow.com/questions/71031091/android-12-using-schedule-exact-alarm-permission-to-get-show-data-at-specific-t

[^4_23]: https://www.reddit.com/r/androiddev/comments/1gy1djf/declaration_required_by_google_play_on_using/


---

# должно работать не на сервере, а прямо на устройстве. давай тогда частоту сделаем минимум каждые 5 минут. так возможно ли?

Да, раз в 5 минут — возможно без постоянного foreground‑сервиса и без «падения через 6 часов».

Рекомендованный способ:

- Используй WorkManager с PeriodicWorkRequest. Минимальный поддерживаемый системой интервал — 15 минут. Поэтому сделай связку:
    - Точный будильник AlarmManager.setExactAndAllowWhileIdle каждые 5 минут, который будет запускать OneTimeWorkRequest.
    - Для доступа к точным будильникам запроси SCHEDULE_EXACT_ALARM (покажи системный экран “Будильники и напоминания”). Без этого система может не будить устройство каждые 5 минут.
    - В задаче выполняй короткую проверку (несколько секунд) и сразу заверши работу, не поднимая FGS; если нужно уведомление — обычный Notification.

Альтернатива, если без разрешения на точные будильники:

- Используй только WorkManager PeriodicWorkRequest, но тогда минимально — раз в 15 минут (с flex-интервалом). Каждые 5 минут без SCHEDULE_EXACT_ALARM система не гарантирует.

Итого:

- 5 минут реально, но нужно SCHEDULE_EXACT_ALARM и одноразовые setExact с перепланированием.
- Не держи постоянный FGS; так ты не упрёшься в лимит 6 часов Android 15.
<span style="display:none">[^5_1][^5_2][^5_3]</span>

<div style="text-align: center">⁂</div>

[^5_1]: 1000827193.jpg

[^5_2]: 1000827191.jpg

[^5_3]: 1000827192.jpg


---

# Можно ли сохранить два уведомления и «закреплённое» служебное при переходе на безопасную схему без вечного FGS

Короткий ответ: да. Постоянное «служебное» уведомление можно оставить как обычное ongoing-уведомление без запуска бесконечного foreground-сервиса; всплывающее уведомление о новом сообщении остаётся как отдельное уведомление с контентом. Важно лишь не держать длительный FGS типа dataSync, который на Android 15 ограничен суммарно 6ч/сутки и вызывает onTimeout/исключение по исчерпании лимита[^6_1][^6_2][^6_3].

## Как перестроить

- Служебное закреплённое уведомление
    - Формируй обычное Notification с флагом ongoing=true, обновляй счётчики через периодические задания/будильники; FGS для этого не обязателен[^6_4].
    - Это позволит держать «шторку» с прогрессом/статистикой постоянно, не попадая под 6‑часовой лимит FGS[^6_1][^6_2].
- Уведомление о новом сообщении
    - Оставь как отдельное стандартное уведомление, показываемое при срабатывании работы/будильника и обнаружении нового сообщения; FGS не требуется[^6_5].
- Периодический запуск без «вечного» сервиса
    - Если нужна частота ≥15мин, используй PeriodicWorkRequest в WorkManager; он не требует FGS и не упирается в 6ч-ограничение[^6_5].
    - Если нужна частота 5–10мин, применяй точные будильники: setExactAndAllowWhileIdle() + разрешение SCHEDULE_EXACT_ALARM/экран «Будильники и напоминания»; из ресивера будильника запускай короткий OneTimeWorkRequest и обновляй оба уведомления[^6_6][^6_7][^6_5].
    - Помни, что начиная с Android 14 точные будильники по умолчанию не выдаются — доступ нужно запрашивать осознанно и экономно использовать[^6_7][^6_5].


## Когда всё же нужен FGS

- Запускай FGS только на короткое время под конкретную операцию и с корректным foregroundServiceType; для dataSync и mediaProcessing действует лимит 6ч/сутки и при его достижении система вызывает Service.onTimeout() и требует stopSelf()[^6_1][^6_2].
- При попытке перезапуска после исчерпания лимита получишь ForegroundServiceStartNotAllowedException («Time limit already exhausted…»), пока пользователь не выведет приложение на передний план[^6_2][^6_8].
- Поэтому «вечно закреплённое» уведомление не должно быть привязано к FGS — держи его как обычное ongoing-уведомление и обновляй по расписанию[^6_4][^6_1].


## Итоговая схема для сохранения принципа двух уведомлений

- Постоянное служебное уведомление: обычное ongoing, обновляется каждые 5–15мин задачей/будильником — без FGS[^6_4][^6_5].
- Всплывающее уведомление о новом сообщении: показывается по факту обнаружения — без FGS[^6_5].
- Никаких бесконечных FGS: так избежишь тайм-аута Android 15 на 6ч и падений[^6_1][^6_2][^6_3].
<span style="display:none">[^6_10][^6_11][^6_12][^6_13][^6_14][^6_15][^6_16][^6_17][^6_18][^6_19][^6_20][^6_21][^6_22][^6_23][^6_9]</span>

<div style="text-align: center">⁂</div>

[^6_1]: https://developer.android.com/develop/background-work/services/fgs/timeout

[^6_2]: https://developer.android.com/about/versions/15/behavior-changes-15

[^6_3]: https://softices.com/blogs/android-foreground-services-types-permissions-use-cases-limitations

[^6_4]: https://github.com/invertase/notifee/issues/958

[^6_5]: https://developer.android.com/develop/background-work/services/alarms/schedule

[^6_6]: https://learn.microsoft.com/ru-ru/dotnet/api/android.app.alarmmanager.setexactandallowwhileidle?view=net-android-34.0

[^6_7]: https://developer.android.com/about/versions/14/changes/schedule-exact-alarms

[^6_8]: https://github.com/home-assistant/android/issues/5338

[^6_9]: 1000827193.jpg

[^6_10]: 1000827191.jpg

[^6_11]: 1000827192.jpg

[^6_12]: https://www.linkedin.com/posts/mangeshkokare_androiddev-android15-foregroundservices-activity-7237313714771673088-XX-Q

[^6_13]: https://github.com/Catfriend1/syncthing-android/issues/1147

[^6_14]: https://developer.android.com/about/versions/14/changes/fgs-types-required

[^6_15]: https://www.reddit.com/r/androiddev/comments/1csvfe7/android_15_beta_2_new_changes/

[^6_16]: https://stackoverflow.com/questions/71031091/android-12-using-schedule-exact-alarm-permission-to-get-show-data-at-specific-t

[^6_17]: https://proandroiddev.com/whats-new-in-android-14-for-developers-1e5d7d8b3482

[^6_18]: https://commonsware.com/blog/2024/05/18/random-musings-android-15-beta-2.html

[^6_19]: https://www.zebra.com/content/dam/zebra_dam/en/presentation/customer-facing/zebra-devcon2023-presentation-customer-facing-getting-your-application-ready-pietroandnicola-dezolt-en-us.pdf

[^6_20]: https://github.com/FossifyOrg/Calendar/issues/217

[^6_21]: https://www.reddit.com/r/androiddev/comments/13pyzgm/workmanager_praise/

[^6_22]: https://github.com/thudugala/Plugin.LocalNotification/issues/462

[^6_23]: https://habr.com/ru/companies/usetech/articles/754756/

