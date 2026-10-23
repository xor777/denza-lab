# Пассивный статус ADAS

Изолированный диагностический APK `dev.denza.adasstatus.probe`, без зависимостей
продуктовых приложений и без подключения к корневому Gradle build. Читает ответы
ADAS через штатный SOME/IP **client** Binder и показывает последнее наблюдение
в Activity и, по разрешению пользователя, поверх карты.

## Current state

| Claim | Status | Section |
| --- | --- | --- |
| APK собирается локальным builder, подпись проверена; 46 host checks и replay 384 реальных пакетов проходят | local | [Сборка](#сборка) |
| Только пять клиентских подписок, без публикации маршрутов, `fireEvent`, setters и переключателей DNP/ICC | code | [Протокол](#протокол) |
| DNP/ICC `1` = активен, `2` = доступен; NOA mode `1` = трассовый, `2` = городской, отдельно от доступности | firmware | [Семантика](#семантика) |
| Оверлей виден поверх обычного экрана машины; запуск, устаревание и остановка проверены на отдельном эмуляторе | device | [Интерфейс](#интерфейс) |
| На машине получен 1371 пакет: по 457 pilot/alarm/mode, без ошибок декодирования и потерь очереди | car, 2026-10-23 | [Приёмка](#приёмка) |

Содержание: [Сборка](#сборка) · [Протокол](#протокол) ·
[Семантика](#семантика) · [Интерфейс](#интерфейс) ·
[Эмулятор](#эмулятор) · [Лог](#лог) · [Приёмка](#приёмка) · [Границы](#границы).

## Сборка

```sh
python3 tools/adas_status_build.py captures/yandex-adas-prototype-20261023/observer-build-NN
```

Builder только компилирует, проверяет host tests, создаёт APK и проверяет подпись.
Команд ADB, установки или запуска в нём нет. Каждый build требует нового output
каталога; `manifest.json` содержит SHA-256 APK и всех source inputs, а также
результаты тестов. Отдельный локальный ключ совместимых обновлений хранится в
git-ignored `captures/adas-status-signing/probe.p12`. APK и ключ не коммитятся.

Источники: `src/main/java/dev/denza/adasstatus/probe/`; Activity:
`dev.denza.adasstatus.probe/.StatusActivity`. Требуются API 26+, стандартные
`FOREGROUND_SERVICE` и пользовательское разрешение `SYSTEM_ALERT_WINDOW`.
BYD/system permissions, boot receiver, INTERNET и exported service отсутствуют.

## Протокол

Component:
`com.ts.car.someip.service/.manager.SomeIpClientService`.
Descriptor: `ts.car.someip.sdk.ISomeIpClientInterface`.
Intent type — собственный package + случайная session UUID.

| Service/event | Данные |
| --- | --- |
| 13 / `0x8002` | ACC, ICC, DNP, takeover status, driving time |
| 13 / `0x8003` | alarm/notice reason, stage, distance, timestamp — raw |
| 13 / `0x8005` | NOA mode, notice |
| 44 / `0x8002` | selected path ID, status code, error; `200` — success |
| 44 / `0x8001` | PathMatch code/message и RouteInfo array |

Разрешённые исходящие client-транзакции: `1` register, `2` unregister,
`4` startClient, `5` stopClient, `8` subscribe, `9` unsubscribe. Удаляются только
собственные подписки/клиенты; bulk stop, request, setAttribute и server API
отсутствуют. На dead/null binding сделаны максимум три повторных bind; ошибка
частичного setup видна в логе/Activity и требует ручного перезапуска.

Native callback выполняется под lock списка callback-ов прошивки. Поэтому он
только читает ограниченный Parcel (до 64 КиБ), отмечает monotonic время приёма,
пытается положить работу в очередь на 64 элемента и немедленно отвечает.
Декодирование, hex, файлы и UI в callback не выполняются. Переполнение очереди
считается. Ответ `onSomeIpEvent` содержит `writeNoException` и echo того же
`SomeIpData`, как требует SDK; `onRequest` возвращает null response + echo request.
Старые queued callbacks не переходят через connection epoch. Stop прекращает
приём, удаляет оверлей и делает текущий UI UNKNOWN до асинхронного cleanup.

Формат и номера полей взяты из:

* `captures/adas-bridge-scout/ref/hud/app/src/main/java/com/bydhud/app/ShanghaiSomeIpClient.java`;
* `captures/hud-firmware-20260923/jadx/BydLaunchermap/sources/ts/car/someip/sdk/{ISomeIpClientInterface,ISomeIpCallback,SomeIpData}.java`;
* там же `someip/pilot/status_/alarm/info/service/SomeipPilotStatusAlarmInfoService.java`
  (PilotStatusNotifyStruct, PilotAlarmNoticeInfoNotifyStruct, BroadcastInfoNotifyStruct);
* там же `someip/navigation/path/match/status/service/SomeipNavigationPathMatchStatusService.java`.

Вход — protobuf из `SomeIpData` SDK, а не сырой SOME/IP packet. Для известных
полей проверяется wire kind; protobuf int64 path ID сохраняется целым. Unknown
fields пропускаются. Host checks включают неверный outer fixed64, который ранее
мог имитировать корректные поля AVAILABLE, обрезанные varints,
точный `Long.MAX_VALUE`, ACK code и state/freshness invariants.

## Семантика

* DNP/ICC/ACC `0`: не предлагается / выключен (два случая не различаются здесь);
  короткая подпись в UI — «неактивен».
* `1`: активен; `2`: доступен. Иные значения — UNKNOWN.
* NOA mode `1`: highway / HNOA; `2`: city / CNOA. Это отдельное последнее
  наблюдение и **не** признак доступности.
* Selected path code `200`: подтверждён последний native ACK для указанного ID.
  Это не доказывает совпадение с маршрутом Яндекса или отсутствие конкурирующего
  штатного publisher.
* Alarm/notice поля показаны raw: без придуманной расшифровки причин/TOR.

Heartbeat/event cadence ADAS не установлена. Пять секунд — только политика UI
«недавний ответ», не native expiry и не доказательство, что ECU перестало
предлагать функцию. При тишине, disconnect/HAL false верхняя строка становится
`NOA: нет данных`, а последнее raw значение остаётся в подробностях.
Ни один timeout не превращается в DNP `0`. Возраст считается от времени приёма
в Binder callback, не от более позднего dequeue. UI пересчитывает возраст на main
thread из immutable snapshot; backlog логирования не делает событие свежим.

## Интерфейс

Приложение называется «Статус ADAS». Activity содержит подробности, кнопки старта,
оверлея и остановки. Оверлей —
`TYPE_APPLICATION_OVERLAY`, `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE`, справа
сверху, ширина 720 px, шрифт 40 px. Пять строк: NOA, ICC, режим, маршрут,
возраст ответа. Числовые значения протокола остаются в подробностях и журнале.

Оверлей создаётся только после стандартного разрешения пользователя. Он не
занимает HUD, не перехватывает касания и не запрашивает фокус. Foreground
notification позволяет открыть Activity и остановить наблюдение. Stop удаляет
оверлей и останавливает service; boot/autostart не предусмотрены.

## Эмулятор

Только при `Build.HARDWARE == ranchu/goldfish` Activity принимает intent extra
`fixture`: `available`, `active`, `unavailable`, `city`, `highway`, `stale`,
`disconnected`. На других hardware fixtures отвергаются с записью в лог.
Дополнительный boolean `overlay=true` показывает оверлей, если разрешение уже дано.

Например, после отдельной установки на выделенный эмулятор:

```sh
adb -s emulator-5592 shell am start \
  -n dev.denza.adasstatus.probe/.StatusActivity \
  --es fixture city --ez overlay true
```

Все fixtures имеют заголовок `Эмулятор`, а события — `source=emulator_fixture`.
Эмуляторная картинка доказывает UI/decoder, а не доступность ADAS на машине.

## Лог

`files/adas-status.jsonl` + `files/adas-status.previous.jsonl`, максимум два файла
по 4 МиБ; ротация выполняется на worker. Logcat tag: `DenzaAdasStatus` (короткая
decoded сводка, полные bytes сохраняются в файлах).

Изменения значений пишутся отдельно: `files/adas-status-changes.jsonl` и
`files/adas-status-changes.previous.jsonl`, два файла по 512 КиБ;
logcat tag `DenzaAdasChange`. В журнал попадают DNP/ICC/ACC, тип NOA,
подтверждения маршрута и коды предупреждений. Изменения счётчиков, timestamps и
дистанций не создают повторную запись; исходные пакеты остаются в полном журнале.

Снять журнал с установленного приложения:

```sh
adb -s 127.0.0.1:5555 shell run-as dev.denza.adasstatus.probe cat files/adas-status-changes.jsonl
adb -s 127.0.0.1:5555 logcat -v time -s DenzaAdasChange:I
```

Native event:

```text
schema=denza-adas-passive-event-v1
event=someip
source=native_someip | emulator_fixture
topic, timestamp, declaredLength, payloadHex
receivedAtUptimeMs, processedAtUptimeMs
capturedAtMs, capturedUptimeMs, droppedEvents
decoded={event, dnp/icc/acc/...} | decodeError
```

`timestamp` — исходное SDK значение; `receivedAtUptimeMs` — локальный приём;
`captured*` — время записи. Control events имеют source `native_client_control`.
`payloadHex` остаётся исходным, даже если декодирование отвергнуто.

## Приёмка

2026-10-23, `observer-build-05`: 1371 реальный callback за 45,6 с,
по 457 `pilotStatus`, `alarm`, `noaMode`. Ошибок декодирования и переполнения
очереди нет, максимальная задержка обработки 32 мс. DNP/ICC/ACC — 0,
тип NOA — 2, alarm reason/stage/notice — 0. В журнале изменений три начальных
наблюдения; service 44 за этот интервал не приходил. Crash buffer не изменился.
Исходный APK, установленный APK и manifest совпали по SHA-256.

У build-04 alarm timestamps ошибочно читались как integer; реальные поля 6/9 —
double. Ошибка исправлена в build-05 и покрыта replay всех 384 первых callbacks.
Эти timestamps сохраняются без предположения о единицах. `takeoverStatus` — bool.
Для service 44 есть тесты формата; реальный ответ этим APK пока не записан.

В build-06 по просьбе владельца шрифт увеличен с 20 до 40 px, ширина с 530 до
720 px, убраны пояснительные тексты из интерфейса. Подписки и декодер не менялись.
Обновление установлено на машину; проверены 267 новых пакетов (по 89 каждой
темы), ошибок и потерь нет. Установленный SHA-256 совпал со сборкой, crash buffer
не изменился. Новый вид проверен по скриншоту; наблюдение оставлено включённым.
Сводка с хешами и результатом установки:
[`observer-evidence-20261023.json`](../../research/adas-bridge/observer-evidence-20261023.json).

## Границы

Это observer, а не проверка работоспособности NOA и не route publisher. Binder
connected и HAL available сами по себе не подтверждают доступность ADAS. Прошивка
может не повторять неизменившиеся состояния после поздней подписки; отсутствие
пакетов означает UNKNOWN. Приём реальных pilot/alarm/mode и оверлей проверены на
машине; активация NOA и семантика route-match этим экспериментом не проверялись.
