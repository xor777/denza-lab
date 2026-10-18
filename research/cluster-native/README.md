# Native-библиотеки приборки: корпус и разбор

Офлайн-разбор файлов с машины от 2026-10-18 для повторного исследования
приборки, навигации, проекции, медиаданных и ADAS. Здесь находятся инструменты
и выводы; исходные ELF, извлечённые данные и дизассемблер — в git-ignored
`captures/`. Код прошивки при анализе не исполнялся.

## Current state

| Claim | Status | Evidence |
| --- | --- | --- |
| Две библиотеки, `BydClusterManager` и стартовый скрипт совпали с файлами машины по SHA-256 | live, 2026-10-18 | [corpus.json](corpus.json), `captures/adas-speed-20261018/reports/cluster-native-provenance.json` |
| Полностью дизассемблированы секции `.text` трёх ELF; собраны символы, строки, прямые вызовы и имеющийся DWARF | code | `captures/cluster-native-20261018/verification.json` |
| Из Cells извлечены 2 022 записи сигналов, 1 373 элемента данных, 102 настройки, 81 сообщение | code | `tables/`, размеры и смещения сверены с DWARF |
| Из Client распаковано встроенное описание API: 153 класса, 858 записей | code metadata | `client-api/manifest.json`, `client-api/api.tsv` |
| Из текущего `cluster_hmi.pck` извлечены все 375 скриптов; их MD5 совпали с каталогом, токенизированные буферы полностью разобраны | firmware | [Скрипты Godot](#скрипты-godot), `pck/scripts-manifest.json` |
| Разобрана бинарная RSRC-сцена карты; исходные ASTC-маски и shader сохранены для отдельного визуального пробника | firmware | [oversea-map-probe](../../experiments/oversea-map-probe/README.md), `read_binary_scene.py`, `captures/oversea-map-probe-20261018/` |
| В Cells разобраны 1 030 Android packed RELA; GOT чтения потока указывает на `g_can_service_node_name` | firmware | [Транспорт TOR](#транспорт-tor), `libCellsClusterDs/android-rela.tsv` |
| Между библиотеками совпадают все 1 373 имени/значения DataItem и 83 имени/81 значение PluginMsg | code | `verification.json`, DWARF обоих ELF |
| Изученные функции скорости публикуют данные интерфейса; место ограничения ACC/ICC/NOA 130 км/ч не найдено | open | [Разобранные функции](#разобранные-функции), [исследование ADAS](../../docs/adas-operating-limits.md) |
| Загрузка этих библиотек действующим процессом на этой машине пока не доказана | open | [Присутствие и выполнение](#присутствие-и-выполнение) |

## Contents

- [Что сохранено](#что-сохранено)
- [Карта кода](#карта-кода)
- [Таблицы и встроенное API](#таблицы-и-встроенное-api)
- [Разобранные функции](#разобранные-функции)
- [Транспорт TOR](#транспорт-tor)
- [Скрипты Godot](#скрипты-godot)
- [Присутствие и выполнение](#присутствие-и-выполнение)
- [Повторение и поиск](#повторение-и-поиск)

## Что сохранено

Оригиналы: `captures/firmware/pulled-from-car-20261018/native/`.
Результаты ниже находятся в `captures/cluster-native-20261018/`.
Полные хеши и относительные пути сохранены в [corpus.json](corpus.json).

| Файл | Размер | Адресов функций¹ | DWARF | Инструкций `.text` |
| --- | ---: | ---: | --- | ---: |
| `libCellsClusterDs.so` | 17 834 072 | 13 352 | v5, 66 compilation units | 548 446 |
| `libClusterClient.android.release.so` | 29 886 784 | 11 875 | v4, 51 compilation units | 828 111 |
| `BydClusterManager` | 79 560 | 138 | `.debug_info` отсутствует | 10 495 |

¹ Различные адреса входа из символов и `.eh_frame`. Это включает библиотечный
код, шаблоны и вспомогательные функции, а не только бизнес-логику.
`dwarf-functions.tsv` содержит также объявления и повторения; его число строк
не является числом реализованных функций.

Каждый каталог `libCellsClusterDs/`, `libClusterClient/`, `BydClusterManager/`:

| Артефакт | Содержание |
| --- | --- |
| `metadata.json` | ELF SHA-256, зависимости, секции, счётчики, ограничения анализа |
| `functions.tsv`, `symbols.tsv` | Адреса, размеры, mangled и demangled имена; символы включают импорты |
| `strings.tsv` | Читаемые UTF-8 строки из `.rodata`, `.data`, `.data.rel.ro` с адресами |
| `branches.tsv` | Прямые BL, переходы B к функциям, места косвенных BLR/BR |
| `disassembly.txt.gz` | Дизассемблер всей `.text`, имена функций и известных целей вызова |
| `compilation-units.tsv`, `source-lines.tsv` | Пути исходников и соответствие адресов строкам из DWARF |
| `dwarf-functions.tsv` | Объявления/определения функций, исходные имена, адресные интервалы |
| `dwarf-layouts.jsonl`, `dwarf-enums.jsonl` | Размеры классов/структур, смещения полей, перечисления |

Файлы DWARF создаются только при наличии `.debug_info`. Полных оригинальных
C++-исходников здесь нет. Высокоуровневый декомпилятор не запускался; выводы
ниже получены чтением инструкций, символов, типов и таблиц.

Дополнительные указатели в корне результатов:

- `domain-functions.tsv`: 2 234 функции предметных модулей, транспорта,
  конфигурации и клиентских обработчиков, выделенные по именам.
- `data-source-api-calls.tsv`: 134 прямых ребра вызовов `BydDataSource*`.
- `selected-*-functions.asm`: небольшие подборки функций, разобранных ниже.
- `verification.json`: сверка хешей, полного непрерывного покрытия `.text`,
  общих перечислений и количества записей.

## Карта кода

`libCellsClusterDs` содержит проект `BydDataSource/data_source`: CAN-чтение,
запись, синхронизацию и socket-клиент, `DataItemContainer`, `ConfigureManager`,
`PluginMsgManager`, Fission и семейство `Business*`.

`libClusterClient` — Godot GDExtension из `BydClusterGodot/cluster_gdextension`.
Его `DT_NEEDED` прямо включает `libCellsClusterDs.so`. Помимо генерируемых
моделей он содержит `HMIClusterClient`, `MiddleWareComunication` (именно такое
написание в символах) и обработчики `*MsgHandle`.

```mermaid
flowchart LR
    CAN[CAN readers / CanDataWrapper] --> B[Business modules]
    B --> D[DataItemContainer / DataItemWrapper]
    D -->|callbacks: id, value| M[MiddleWareComunication]
    M --> H[MsgHandle / HMIClusterClient]
    H --> G[Godot models and signals]
    G -->|client send methods| P[BydDataSourceSendPluginMsg]
    P --> PM[PluginMsgManager / handlers]
```

Это карта статических связей. Косвенная диспетчеризация не восстановлена
полностью; схема не утверждает, что весь этот путь сейчас запущен.

| Область | Cells: начать с | Client: начать с |
| --- | --- | --- |
| ADAS и дорожная сцена | `BusinessAdas`, `BusinessAdasSR`, `BusinessAdasCommon` | `AdasMsgHandle`, классы `Adas*` |
| Проекция | `BusinessAdasProjectionSR`, `FissionManager` | `ProjectionMsgHandle`, `AdasProjector*` |
| Навигация | `BusinessNavi`, `BusinessAmap` | `MenuMsgHandle`, классы `MapNavi*` |
| Скорость, единицы, общие состояния | `BusinessUi2`, `BusinessCommon`, `BusinessAlwaysDisplay` | `GaugeMsgHandle`, `CommonMsgHandle` |
| Заряд, энергия, поездки | `BusinessCharge`, `BusinessEnergyFlow`, `BusinessJourney` | `ChargeDisMsgHandle`, `JourneyMsgHandle` |
| Музыка и тексты | `BusinessMedia` | `MediaMsgHandle`, классы `Media*` |
| Предупреждения и индикаторы | `BusinessWarningLight`, `Not261WarningInfoManager` | `PopupMsgHandle`, `TelltaleMsgHandle`, `AdasPopup` |
| Оформление и локализация | `BusinessSkin`, `BusinessTheme`, `ConfigureManager` | `HMIClusterClient`, классы `Skin*` |

Сами библиотеки не являются пассивными парсерами. В Cells есть
`CanDataWriter`, а инициализация запускает потоки. Даже некоторые функции
предупреждений выполняют CAN-записи. Для изучения достаточно офлайн-инструментов;
вызов `BydDataSourceInit` в произвольном тестовом процессе не является простым
чтением библиотеки.

## Таблицы и встроенное API

`tables/` содержит:

- `can-signals.tsv`: FID, real FID, длина, тип, признаки repeated/sync,
  `no_need_platform`, статические байты данных. Это таблица записей, а не
  готовая расшифровка физического смысла каждого сигнала.
- `data-items.tsv`: ID, имя, тип и `need_secret` для 1 373 элементов HMI.
- `configuration.tsv`: ID, имя, тип и **скомпилированное значение по умолчанию**.
  Это не текущие значения машины и не результат загрузки XML-конфигурации.
- `plugin-messages.tsv`: 81 ID, имя, тип сообщения. В enum 83 имени из-за
  алиасов границ диапазонов; все имена одного значения сохранены через `|`.
- `checked-layouts.json`, `table-manifest.json`: проверенные определения
  структур, хеш ELF и условия проверки.

Декодер проверяет размер таблицы против её счётчика, смещения полей против
DWARF, разрешение ID/типов в enum и файловые указатели строк. Он не разрешает
все runtime relocations и не читает живые значения.

В Client символ `_doc_data_compressed` по адресу `0x170090` содержит 59 919
байт zlib. Он распаковывается в 392 330 байт последовательных XML-описаний.
Извлечены `client-api/classes/*.xml`, `classes.tsv`, `api.tsv` и исходная
последовательность `embedded-classes.xml` (несколько XML-документов, не один).
API содержит исходные китайские пояснения, типы полей, методы, сигналы и
константы. Это метаданные из бинарника; фактическое поведение проверяется по
коду. Для быстрого начала полезны классы `HMIClusterClient`, `AdasPopup`,
`AdasProjectorContent`, `AdasProjectorRequest` и `MapNavi*`.

## Разобранные функции

Все адреса — виртуальные адреса ELF при нулевой базе, до ASLR.

**Граница библиотек.** `MiddleWareComunication::Init` в Client (`0x4024d4`)
создаёт обработчики и вызывает `BydDataSourceInit`, передавая две таблицы
callbacks и размеры `1920 × 1080`. Это константы данного вызова, а не измерение
экрана машины. Cells `BydDataSourceModule::Init` (`0x14e9e0`) последовательно
инициализирует сведения о продукте/экране/ADAS, контейнер данных, сообщения,
CAN, конфигурацию и бизнес-модули, затем запускает потоки.

`DataItemWrapper::SetDataItem_INT` (`0x151690`) проверяет тип и необходимость
обновления, меняет локальное значение под mutex и вызывает callback
`m_cbUpdateInt(id, value)` через поле `+8` таблицы callbacks. Соответствующий
DWARF-тип `data_item_upadte_callbacks_type_` имеет размер 40 байт в обеих
библиотеках. Эта операция сама по себе не является записью заданной скорости
в контроллер автомобиля.

**Заданная скорость ACC.** `BusinessAdasSR::updateAccSpeedLight`
(`0x1ba4c0`) читает входные сигналы состояния и FID `0x2d500038`, переводит
сырое значение с коэффициентом `0.5` в целое значение и публикует DataItem
`0x452 / DATA_ITEM_ID_ACC_SPEED_VALUE / accSpeedValue`. Невалидная ветка
скрывает индикатор и публикует `0xfff`. Сравнения с 130 в этой функции нет.
Это вывод об одной функции, не обо всём ADS.

**Отображаемое ограничение.** `BusinessUi2::updateSpeedLimit` (`0x183fa0`):

```text
valid = read_u8(0x12000000)
value = read_u8(0x12000020)
if valid == 1 and value <= 240:
    publish DataItem[0x350 / speedLimitValue] = value
    return true
publish DataItem[0x350 / speedLimitValue] = 255
return false
```

Это смысловая реконструкция машинного кода. Здесь проверяется и публикуется
входное значение для интерфейса; изменение потолка ACC/ICC/NOA не обнаружено.
`BusinessUi2::updateCountryCode` (`0x181450`) публикует `0x39 / countryCode`
и вызывает обновление product platform в обоих ADAS-модулях. Это точка входа
для сравнения регионов, но ещё не установленная связь с пределом круиза.

**TOR.** `BusinessSkin::updateTorPath` (`0x2d4170`) проверяет существование
файла, переставляет коды языка `2 ↔ 3`, выбирает конфигурацию и при изменении
пути сохраняет её. `getTorConfigId` (`0x2d9740`) выбирает
`0x60 / tor_path_en` при коде 2, иначе `0x5f / tor_path_zh`.
`updataTorPathInfo` (`0x2d9790`) формирует строку вида
`{"language": N, "path": "..."}` и публикует
`0x540 / DATA_ITEM_ID_TOR_PATH_INFO / torPathInfo`.
Это передача пути словаря. Производитель события `TOR_144`/`TOR_145` и
проверка скорости для него в контроллере пока не установлены. Цепочка
передачи номера события внутри приборки разобрана ниже.

**Предупреждения имеют побочные эффекты.**
`BusinessAdas::centerWarningInfoTextIdUpdate` (`0x1a2420`) пуст.
Версия `BusinessAdasSR` (`0x1b6b30`) отслеживает IDs `0x37` и `0x38` и
вызывает `CanDataWriter::WriteData` при входе/выходе из этих состояний.
Эти IDs не сопоставлены с `TOR_144`/`TOR_145`. Функции не вызывались.

## Транспорт TOR

`BusinessAdasSR::IntelligentFunction` (`0x1bd630`) читает массив
`GetData_8BYTES(0x99000331)`. При выполнении условий видимости/валидности
форматирует его как `%02x %02x %02x %02x %02x %02x %02x %02x` и публикует
DataItem `0x4cc / intelligentDrivePromptInlandValue`.
`PopupMsgHandle::OnDataItemUpdateString` в Client (`0x446898`, ветка `0x4cc`)
получает уровень из байта 0 и номер события из байтов 1–2 (little-endian),
составляет `TOR_<номер>_L<уровень>` и испускает `sig_adas_toast`.
См. `search-tor-ingress.asm`, `search-client-popup.asm`.

В Cells `CanReadThreadBase::GetInstance` создаёт `CanHandleReadThread`.
Его `ThreadFunction` (`0x115080`) открывает путь через GOT `0x32f960`
и передаёт прочитанный поток в `ProcessCanData`. Packed RELA разрешает этот
GOT в `g_can_service_node_name` (символ `0x335690`), начальная строка которого:
`/sys/devices/platform/soc/4a80000.spi/spi_master/spi0/spi0.0/spidev_cluster`.
Сам узел не открывали: такой read может потреблять поток событий.
Разбор пакетов вызывает `DispatchFunctionData`/`DispatchGroupData`, обновляет
кэш значений и вызывает зарегистрированные обработчики.

`extract_android_rela.py` читает только APS2 RELA и не применяет relocations.
Алгоритм формата сверен с [исходником Android linker](https://android.googlesource.com/platform/bionic/+/refs/heads/main/linker/linker_reloc_iterators.h),
blob `c6a8cf620ab2f7d3ea1c7e921f12e06743265fa2` на дату анализа.
Все 1 030 записей Cells разобраны с полным потреблением секции;
обычные RELA и RELR не включены в этот дополнительный TSV.

Это граница источника данных **этой реализации**, не установление физического
CAN ID, конкретного ADS ECU или его алгоритма. Источник события до приборки
не разобран.

Дополнительная проверка `BusinessAdas::updateSpeedFlag` (`0x194650`)
различает диапазоны 30–150 mph и 50–240 км/ч и обновляет предупреждение
3019. Здесь 150 — значение в милях в час, не найденный экспортный потолок.
`search-speed-gates.asm`, `search-speed-popup.asm` сохраняют инструкции.

## Скрипты Godot

Полный `/system/lib64/cluster_hmi.pck` на машине: 466 734 224 байта,
SHA-256 `9fc84b671ab21024d432082a38a92fe576e5773747d0dcfe37ee76f745cb17e0`.
Скопирован его начальный диапазон 2 097 152 байта в `cluster_hmi.pck.header`;
это **не полный пакет**. Формат 2, engine 4.3.0, flags 0, база содержимого
1 872 752. Каталог содержит 15 005 файлов и заканчивается на 1 872 740.
`read_pck_header.py` повторяет разбор каталога и проверяет границы всех записей
против полного размера исходника.

Все 375 `.gdc` (1 548 804 байта) извлечены 60 объединёнными диапазонами
чтения общим объёмом 1 961 984 байта; каждый файл проверен по MD5 каталога.
Также извлечены `byd_ads_zh.json` и `byd_ads_en.json`: в обоих TOR_144/145
содержат 130 км/ч. Наличие английского текста не доказывает экспортную калибровку.

`decode_gdc.py` распаковывает Zstandard и читает tokenizer version 100,
таблицы идентификаторов/констант, строки/колонки и все токены.
Формат сверен с [кодом Godot 4.3](https://github.com/godotengine/godot/blob/4.3/modules/gdscript/gdscript_tokenizer_buffer.cpp);
официальные исходники и их хеши сохранены в `pck/format-source/sources.json`.
Для всех 375 файлов подтверждено полное потребление буфера. `.gd` —
читабельная запись токенов с исходными номерами строк и нормализованными
пробелами, без комментариев; повторная сборка не проверялась.
Неизвестные типы Variant/версии вызывают ошибку, код скриптов не исполняется.

Артефакты относительно `captures/cluster-native-20261018/pck/`:

| Путь | Содержание |
| --- | --- |
| `directory.json`, `directory.tsv`, `directory-verification.json` | Полный каталог, границы и SHA-256 копии заголовка |
| `scripts-manifest.json` | Все 375 адресов/размеров/MD5/SHA-256 скриптов |
| `extracted-manifest.json` | Первая выборка, включая два словаря JSON |
| `extracted/` | Исходные `.gdc` и два словаря, пути относительно `res://` |
| `decoded/` | Реконструированные `.gd`, точные `.tokens.tsv` и `manifest.json` |
| `speed-literals-search.txt` | Поиск чисел 130/150 по всему набору скриптов |

Полезные точки входа внутри `decoded/`:

- `common/base_services/common_mgr.gd:602` → сигнал `sig_adas_toast`;
  `ui_logic_arbitrate/middle_area_arbitrate.gd:695` → выбор видимости/позиции;
  `common/scene_services/popup_zjd_mgr.gd:124` → выбор словаря.
- `common/scene_services/popup_add/popup_json.gd:170` снимает `_L…`,
  проверяет уровень 1–4 и ищет ключ, `popup_zjd.gd:151` читает поля отображения.
  Условия скорости 130 в просмотренной цепочке нет.
- `scenes/telltale/acc/acc.gd:85` показывает `str(speed)`, кроме 0 и 4095;
  `common/global_define/drive_def.gd:219` задаёт шкалу 150 mph / 240 км/ч.
- `common/global_define/vehicle_config.gd:512` — список `is_oversea`;
  `scenes/drive_map/scripts/drive_map.gd:78` — экспортные маски карты;
  `common/base_services/ui_logic_arbitrate/bg_arbitrate.gd:119` — тёмная тема GAS;
  `scenes_1920_480/drive_map/scripts/drive_map.gd:69` — маски CP/AA/GAS.

Подробное значение этих веток, включая отсутствие отдельного переключателя
`oversea` в просмотренном коде, записано в
[исследовании ADAS](../../docs/adas-operating-limits.md#региональная-ветка-карты).
375 декодированных файлов — полнота извлечения, не заявление о полном
ручном аудите всех функций или доказательство отсутствия ограничения во всём ADS.

## Присутствие и выполнение

Хеши доказывают происхождение файлов с машины, но не их активность.
Сохранённая проверка `pidof BydClusterManager BydClusterGodot BydClusterKanzi
qtandroidnative` не вернула PID; `/proc/<pid>/maps` с загрузкой библиотек
не получен. Дополнительная проверка видит `com.byd.cluster`, PID 3840;
чтение `/proc/3840/maps` получило Permission denied. APK совпал с уже
декомпилированной копией по SHA-256, его код запрашивает проекцию через
ContentProjectionManager. Это не подтверждает загрузку исследованных ELF.
В `startBydCluster.sh` есть выбор Kanzi для серии `F`, Qt для
остальных и sleep при `fission_single_os=1`; это не доказательство исполнения
скрипта. Нельзя объявлять Godot действующей приборкой только по наличию ELF.

## Повторение и поиск

Рабочая директория — корень репозитория. Используется существующая
`.venv-firmware` из [requirements-firmware.txt](../requirements-firmware.txt)
и `c++filt` в PATH. Инструменты читают файлы, не подключаются к машине.

```bash
.venv-firmware/bin/python research/cluster-native/inspect_native.py index \
  captures/firmware/pulled-from-car-20261018/native/libCellsClusterDs.so \
  --out captures/cluster-native-20261018/libCellsClusterDs --assembly

.venv-firmware/bin/python research/cluster-native/inspect_native.py index \
  captures/firmware/pulled-from-car-20261018/native/libClusterClient.android.release.so \
  --out captures/cluster-native-20261018/libClusterClient --assembly

.venv-firmware/bin/python research/cluster-native/extract_tables.py \
  captures/firmware/pulled-from-car-20261018/native/libCellsClusterDs.so \
  --index captures/cluster-native-20261018/libCellsClusterDs \
  --out captures/cluster-native-20261018/tables

.venv-firmware/bin/python research/cluster-native/extract_client_api.py \
  captures/firmware/pulled-from-car-20261018/native/libClusterClient.android.release.so \
  --out captures/cluster-native-20261018/client-api

.venv-firmware/bin/python research/cluster-native/inspect_native.py function \
  captures/firmware/pulled-from-car-20261018/native/libCellsClusterDs.so \
  'BusinessAdasSR::updateAccSpeedLight'

rg --no-ignore 'accSpeedValue|torPathInfo' captures/cluster-native-20261018/tables
rg --no-ignore 'MapNavi|AdasPopup' captures/cluster-native-20261018/client-api/classes.tsv

.venv-firmware/bin/python research/cluster-native/read_pck_header.py \
  captures/cluster-native-20261018/cluster_hmi.pck.header \
  --source-size 466734224 --out captures/cluster-native-20261018/pck

.venv-firmware/bin/python research/cluster-native/decode_gdc.py \
  captures/cluster-native-20261018/pck/extracted \
  --token-names-source captures/cluster-native-20261018/pck/format-source/gdscript_tokenizer.cpp \
  --out captures/cluster-native-20261018/pck/decoded

.venv-firmware/bin/python research/cluster-native/extract_android_rela.py \
  captures/firmware/pulled-from-car-20261018/native/libCellsClusterDs.so \
  --out captures/cluster-native-20261018/libCellsClusterDs
```

`function` принимает regex demangled-имени или точный адрес, например
`0x1ba4c0`. Не более восьми совпадений по умолчанию; меняется через
`--max-matches`. Индексатор отказывается смешивать разные SHA-256 в одном
каталоге и завершает работу ошибкой при неполном дизассемблировании `.text`.
Косвенные вызовы остаются отдельными точками дальнейшего исследования.
Декодеру `.gdc` дополнительно нужен `zstd` в PATH; на машине анализа он уже
установлен. Он используется только как локальный распаковщик.
