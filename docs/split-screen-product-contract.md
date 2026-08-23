# Split Screen: продуктовый и алгоритмический контракт

Статус: рабочая нормативная спецификация перед рефакторингом. Она описывает
ожидаемое поведение, а не текущее устройство кода и не журнал уже выполненных
экспериментов. Текущие расхождения реализации вынесены в конец документа.

## 1. Зафиксированные продуктовые решения

1. Одно и то же приложение разрешено выбирать в обеих панелях. Продукт не
   вводит искусственный запрет по package name. Если конкретное Android-
   приложение технически не поддерживает независимый второй task, неудачная
   попытка не должна перемещать или закрывать первый экземпляр: пользователь
   остаётся в пикере и видит понятное сообщение.
2. Denza Apps в Split Screen является обычным выбираемым приложением. Запуск
   собственного процесса Denza Apps не должен сам открывать, закрывать,
   восстанавливать или перестраивать Split Screen.
3. Последний `Back` внутри выбранного приложения закрывает его task. Под ним
   снова становится виден пикер этой панели. Внутренний back stack приложения
   до последнего `Back` работает штатно.
4. Закрытие одной панели разворачивает оставшуюся панель на весь экран. Это
   нормальный результат, а не дефект.
5. Закрытие последнего полноэкранного пикера завершает продуктовую сессию и
   выводит Home.
6. Пока навигация находится на приборной панели, её освободившийся пикер можно
   использовать. При возврате навигации выбранное во временной вакансии
   приложение закрывается, а навигация возвращается на своё место. Это
   осознанная текущая политика.
7. Overlay запуска показывается только после явного нажатия иконки Split
   Screen. Он не появляется при edge-drag, resize, `Back`, Home, возврате
   навигации или фоновой сверке.
8. Overlay обязан иметь конечный срок жизни. Истечение срока не только скрывает
   окно, но и делает незавершённую операцию недействительной: её поздние
   callback и shell-команды больше не могут менять экран.
9. Живое выбранное приложение нельзя без необходимости перезапускать,
   пересоздавать или заменять другим сохранённым приложением. В частности,
   музыка не должна заменяться ADAS и не должна прерываться при Home, resize,
   повторном открытии Split Screen или возврате навигации.

## 2. Термины и независимые измерения состояния

Состояние нельзя хранить одним enum, смешивающим продуктовый выбор, реальные
Android-задачи и технические lease. Для решения нужны четыре независимых
измерения.

### 2.1. Состояние функции

- `DISABLED` — иконка скрыта, автоматизация не реагирует на split-события.
- `ENABLED` — иконка доступна; явный запуск Split Screen разрешён.

### 2.2. Логическое содержимое панели

- `CLOSED` — панели в текущей продуктовой сцене нет.
- `PICKER` — панель существует, сверху виден пикер.
- `APP(package)` — над постоянным пикером выбранное приложение.

Обе панели могут независимо содержать один и тот же `package`.

### 2.3. Видимая сцена

- `HOME` — Home на IVI.
- `SPLIT(left, right)` — две видимые панели.
- `FULL(content)` — единственная панель на весь экран.
- `HIDDEN(scene)` — продуктовая сцена жива, но перекрыта Home, Recents или
  обычным полноэкранным приложением.
- `PROJECTED(scene, navTask)` — навигация временно находится на приборке.
- `ABSENT` — живой продуктовой сцены нет.

### 2.4. Операция

- `IDLE`
- `OPEN`
- `SELECT(pane, package)`
- `EDGE_COMMIT`
- `RECONCILE`
- `NAVIGATION_RETURN`
- `DISABLE`

У каждой изменяющей операции есть монотонный `operationId`, исходный live
snapshot, deadline и конечный результат: `COMMITTED`, `ROLLED_BACK` либо
`CANCELLED`. Состояния `OPENING`, `ATTACHING`, overlay lease и task ID не
переживают процесс или reboot.

## 3. Неподлежащие ослаблению инварианты

1. При `DISABLED` старт Denza Apps и любого другого приложения не выполняет ни
   одной split-мутации: не закрывает OEM gate, не удаляет stock picker, не
   двигает задачи и не меняет глобальную resizeability.
2. Мутация разрешена только для задачи, root или lease, чья принадлежность
   текущей продуктовой операции подтверждена точной live identity.
3. Package name сам по себе не доказывает identity задачи. Особенно это важно
   для двух одинаковых приложений и для `dev.denza.apps`, где picker и обычное
   приложение принадлежат одному пакету.
4. Task/root ID являются краткоживущими live-наблюдениями. После reboot, update
   или недоказанного process death они не используются как долговечная истина.
5. Home не равен close. Home скрывает сцену, но не очищает выбранные приложения
   и не перезапускает их.
6. Закрытие приложения действительно меняет `APP(package)` на `PICKER` до того,
   как эта панель сможет быть восстановлена в будущем.
7. Закрытие панели действительно меняет её состояние на `CLOSED`; прежнее
   приложение этой панели не воскрешается при следующем явном запуске.
8. Resize без collapse меняет только geometry/bounds. Task ID, PID, package,
   playback и логическое содержимое панели сохраняются.
9. Пассивные lifecycle/accessibility-события являются только сигналами для
   read-only live snapshot. Они не имеют права самостоятельно отменять более
   новый пользовательский ввод.
10. Любая ошибка либо оставляет исходную стабильную сцену, либо приводит к
    новой полностью проверенной стабильной сцене. Непроверенное частичное
    состояние не сохраняется как успешное.
11. После timeout/cancel ни одна команда старого `operationId` не может менять
    задачи, gate, persisted state или overlay новой операции.
12. Обычный запуск приложения после Home остаётся обычным fullscreen-запуском.
    OEM remembered pair не должен самовольно добавлять ADAS, Музыку или другое
    приложение вторым окном.

## 4. Приоритет конфликтующих событий

Если события приходят одновременно или в очереди уже есть старая работа,
действует следующий приоритет:

1. `TOGGLE_OFF` — отменяет все незавершённые split-операции.
2. Подтверждённый `HOME` — отменяет `OPEN`, `SELECT` и passive reconcile и
   запрещает их поздние мутации.
3. Явный navigation lease — временно владеет перемещаемой navigation task.
4. Явный тап приложения в конкретном пикере.
5. Явный тап иконки Split Screen.
6. Подтверждённый edge commit или collapse.
7. Lifecycle/accessibility hints и resize hints.

Событие с меньшим приоритетом не теряется навсегда: после завершения операции
можно прочитать один самый свежий live snapshot. Очередь исторических
`TYPE_WINDOWS_CHANGED` воспроизводить нельзя.

## 5. Включение и выключение

| Исходное состояние | Вход | Ожидаемый результат |
|---|---|---|
| `DISABLED` | Обычный запуск Denza Apps | Обычный fullscreen Denza Apps; ноль split-мутаций. |
| `DISABLED` | Обычный запуск другого приложения | Обычный fullscreen; ноль split-мутаций. |
| `DISABLED` | Включить toggle | Сохранить `ENABLED`, показать иконку. Split и overlay не открывать. |
| `ENABLED`, сцены нет | Выключить toggle | Скрыть иконку; чужую/OEM сцену и задачи не трогать. |
| `SPLIT(APP(A), APP(B))` | Выключить toggle | Отменить автоматизацию, сохранить обе живые задачи и текущий native split. Удалять приложения нельзя. |
| `SPLIT(APP(A), PICKER)` | Выключить toggle | Убрать только подтверждённый product picker; `A` оставить fullscreen. |
| `SPLIT(PICKER, PICKER)` | Выключить toggle | Убрать только оба product picker и показать Home. |
| `FULL(APP(A))` | Выключить toggle | Оставить `A` fullscreen. |
| `FULL(PICKER)` | Выключить toggle | Убрать product picker и показать Home. |
| Любая сцена | Выключить во время операции | Сначала fence/cancel операции, затем выполнить правила выше по новому live snapshot. |

Глобальные lease восстанавливаются только если продукт действительно ими
владеет и после восстановления текущая native-сцена не ломается. Нельзя
безусловно посылать глобальное «закрыть split» при старте выключенной функции.

## 6. Явное открытие и восстановление

| Исходное состояние | Тап Split Screen | Ожидаемый результат |
|---|---|---|
| Нет сохранённых приложений, live-сцены нет | `OPEN` | Overlay сразу; затем `SPLIT(PICKER, PICKER)`. |
| Сохранено `APP(A), PICKER` | `OPEN` | `SPLIT(APP(A), PICKER)`. Живой `A` принять без restart; cold launch только при доказанном отсутствии task. |
| Сохранено `APP(A), APP(B)` | `OPEN` | `SPLIT(APP(A), APP(B))`; каждую живую задачу переиспользовать. |
| Сохранено `CLOSED, APP(B)` | `OPEN` | Не воскрешать закрытое приложение: создать новый `PICKER` рядом с тем же живым `B`. |
| Уже видна корректная product split-сцена | `OPEN` | Принять exact live tasks; ничего не пересоздавать и не перезапускать. |
| `FULL(APP(A))` после collapse | `OPEN` | Сохранить тот же task `A`, создать только недостающий `PICKER`: `SPLIT(APP(A), PICKER)`. |
| `FULL(PICKER)` после collapse | `OPEN` | Сохранить picker task и создать второй picker. |
| Product scene скрыта Home/Recents/чужим fullscreen | `OPEN` | Поднять exact живую сцену. Чужое fullscreen-приложение не удалять. |
| Product scene частично жива | `OPEN` | Принять доказанные живые части; создать только отсутствующие. |
| Неизвестный или OEM split | `OPEN` | Fail closed: не удалять чужие задачи и не запускать OEM remembered pair; показать ошибку. |
| `OPEN` уже выполняется | Повторный тап | Присоединить UI к одной операции. Не запускать второй restore и вторую мутацию. |

Успешный `OPEN` завершается только после read-back, подтверждающего конечную
сцену и её интерактивность. Сам факт возврата shell-команды успехом недостаточен.

## 7. Выбор приложения

| Сценарий | Ожидаемый результат |
|---|---|
| Выбор `A` в `PICKER` | Запустить `A` только в выбранной панели над этим picker; вторую панель не менять. |
| `A` уже работает в другой панели | Попытаться создать независимый второй task; первый task не двигать и не перезапускать. |
| `A` не поддерживает независимый task | Оставить оба окна без изменений; picker снова интерактивен; показать техническое ограничение приложения. |
| Выбрана Denza Apps | Запустить обычный app task Denza Apps над выбранным picker; не вызывать специальное восстановление split. |
| Пакет удалён между показом каталога и тапом | Picker остаётся; список обновляется; persisted state не меняется. |
| Launch crash/redirect/timeout | Picker и сосед остаются; удалить только артефакты этой попытки по exact identity. |
| Двойной/быстрый повторный тап | Одна операция `SELECT`; остальные тапы отклонены видимо или объединены. |
| Home во время `SELECT` | Home побеждает; поздняя задача не появляется поверх Home. |
| Toggle off во время `SELECT` | Toggle off побеждает; поздняя задача не появляется после отключения. |

Persisted `APP(package)` записывается только после проверки, что нужная новая
задача действительно находится над нужным picker. Ошибка сохранения означает
неуспешную операцию и требует rollback/безопасного принятия live-сцены.

## 8. Back, закрытие приложения и закрытие панели

| Исходная сцена | Действие | Ожидаемый результат |
|---|---|---|
| `SPLIT(APP(A), X)` | Внутренний Back, stack не пуст | Только штатная навигация внутри `A`; `X` не меняется. |
| `SPLIT(APP(A), X)` | Последний Back/caption close/swipe task | `SPLIT(PICKER, X)`; сохранённый `A` очищен. |
| `FULL(APP(A))` с picker под ним | Последний Back/caption close | `FULL(PICKER)`; следующий Back закрывает picker. |
| `SPLIT(PICKER, APP(B))` | Закрыть pane с picker | Тот же task `B` становится `FULL(APP(B))`; закрытый slot=`CLOSED`. |
| `SPLIT(PICKER, PICKER)` | Закрыть одну pane | Оставшийся picker становится `FULL(PICKER)`; это намеренное поведение. |
| `SPLIT(APP(A), APP(B))` | Collapse pane `A` | `B` становится fullscreen без restart; slot `A`=`CLOSED`. |
| `FULL(PICKER)` | Back/close | Home; slot=`CLOSED`; продуктовая сцена завершена. |
| `APP(A), APP(B)` | Оба приложения закрыты последовательно | Сначала один, затем второй slot становятся `PICKER`; итог `SPLIT(PICKER, PICKER)`. |

Любое закрытие должно быть подтверждено live snapshot. `onStop` или одно
accessibility-событие не является доказательством: приложение могло лишь быть
перекрыто другим окном.

## 9. Home, обычные приложения и Recents

| Сценарий | Ожидаемый результат |
|---|---|
| Home из `SPLIT(APP(A), APP(B))` | Home; оба task остаются живыми; пара не очищается. |
| Home из `FULL(X)` | Home; survivor остаётся живым в системной topology. |
| Сразу после Home запустить обычное `C` | Только `FULL(APP(C))`; `A`, `B`, ADAS или remembered pair не добавляются. |
| После Home запустить Denza Apps | Обычный `FULL(APP(Denza Apps))`; никаких split-мутаций. |
| Back из обычного fullscreen cover | Если OS сохранила product scene — показать exact живые tasks; Denza ничего не пересобирает. |
| Открыть Recents из split | Recents поверх сцены; logical slots не меняются. |
| Выбрать текущего split-member в Recents | Вернуть его product scene без restart, если прошивка это поддерживает. |
| Выбрать чужое приложение в Recents | Чужое приложение fullscreen; product scene скрыта. |
| Смахнуть выбранное split-приложение | Это close: соответствующий slot становится `PICKER`. |
| Clear all | Закрытые задачи не восстанавливать автоматически; следующий явный `OPEN` показывает picker на их местах. |

Home-gate должен закрываться до того, как следующий обычный launcher intent
сможет вызвать OEM remembered-pair restore. Медленная queued-реакция после уже
открытого приложения недопустима.

## 10. Divider resize, collapse и edge-drag

| Сценарий | Ожидаемый результат |
|---|---|
| Divider двигается без collapse | Только bounds/configuration; приложения, task/PID и playback прежние. |
| Wide/narrow стороны поменялись | После settlement связать логический slot с реально видимой pane; не полагаться на старый root label. |
| Быстрые resize-события | Обработать один последний live snapshot, не очередь старых snapshots. |
| Collapse одной стороны | Survivor fullscreen; закрытая pane=`CLOSED`; удалить только exact owned artifacts. |
| Edge-drag из `FULL(APP(A))` | Пока палец на экране работает только native preview/stock picker; после commit `SPLIT(APP(A), PICKER)`, тот же task `A`. |
| Edge-drag из `FULL(PICKER)` | После commit `SPLIT(PICKER, PICKER)`. |
| Edge-drag отменён | Точное исходное `FULL(X)`; ноль persisted/task/overlay мутаций Denza. |
| Drag/cancel/сразу новый drag | Старый operation generation недействителен; его поздний callback ничего не прикрепляет. |
| Пользователь успел выбрать приложение в stock picker | Принять стабильный OEM результат без удаления приложения либо оставить OEM scene нетронутой; не подменять его поздним picker. |

Denza overlay никогда не участвует в edge-drag. Подготовку каталога можно
делать в фоне, но touch-blocking окно до подтверждённого commit запрещено.

## 11. Навигация на приборной панели

| Исходное состояние | Действие | Ожидаемый результат |
|---|---|---|
| `SPLIT(APP(Nav), APP(Music))` | Перенести Nav на cluster | IVI: `SPLIT(PICKER, APP(Music))`; cluster: тот же Nav task. Music не меняется и продолжает играть. |
| Nav на cluster, vacancy не занята | Вернуть Nav | Тот же Nav возвращается над исходным picker; Music без изменений. |
| Nav на cluster, vacancy занята `Temp` | Вернуть Nav | Закрыть exact task `Temp`, очистить его сохранённый выбор, вернуть тот же Nav на исходное место. Это принятая политика. |
| Companion изменён во время projection | Вернуть Nav | Сохранить текущий companion; не воскрешать старое приложение. |
| Companion pane закрыта | Вернуть Nav | Вернуть Nav рядом с picker либо fullscreen согласно live topology; закрытый companion не воскрешать. |
| Исходная vacancy pane закрыта | Вернуть Nav | Nav возвращается fullscreen; survivor/закрытые slots не пересоздаются. |
| Возврат не удался | — | Nav остаётся на cluster, IVI не очищается; видимый Retry/ошибка. |
| Process death во время projection | — | Не угадывать место по старому task ID; сохранить Nav на cluster до безопасного явного возврата. |
| Toggle off во время projection | Вернуть/оставить Nav по navigation-контракту; product picker убрать; split-автомат больше не двигает задачи. |

Navigation lease блокирует только конфликтующие task moves, а не Home или
выключение. Все изменения vacancy/companion читаются заново непосредственно
перед возвратом.

## 12. Process death, APK update и reboot

| Событие | Ожидаемый результат |
|---|---|
| Process death Denza Apps при живых third-party tasks | Задачи продолжают работать. Cold start сначала делает read-only live snapshot и ничего автоматически не восстанавливает. |
| Process death при видимом picker | Допустимо пересоздать только отсутствующий picker в том же доказанном root; приложения не трогать. |
| APK update | Не удалять survivor и не запускать сохранённые приложения фоном. Явный тап принимает живые задачи и создаёт только недостающее. |
| Reboot | Сохранить toggle и логические slots/package. Task/root ID считать недействительными. Восстановление приложений — только после явного тапа Split Screen. |
| Пакет больше не установлен | Перевести соответствующий `APP(package)` в `PICKER`, показать причину, не повторять бесконечные попытки. |

Инициализация процесса не является пользовательским вводом и потому не имеет
права выполнять task moves, gate close/open или восстановление пары.

## 13. ADB, частичный сбой и rollback

| Сбой | Ожидаемый результат |
|---|---|
| ADB недоступен до первой мутации | Overlay закрывается видимой ошибкой; экран и persisted state исходные. |
| ADB пропал после части команд | Выполнить exact rollback по журналу операции либо принять последнюю полностью проверенную live topology. |
| ADB пропал при стабильном активном split | Оставить native scene работать; не запускать retry-loop, двигающий задачи в будущем. |
| ADB восстановился | Только новый явный ввод или read-only reconcile; никаких отложенных restore. |
| Отдельная команда вернула timeout | Fence operationId; поздний ответ игнорируется. |
| Persist commit не удался | Не объявлять успех; rollback или безопасно принять и повторно зафиксировать проверенную live scene. |

Каждая изменяющая операция ведёт короткий mutation journal: исходные lease,
созданные task, перемещённые exact task и последняя проверенная topology. Это
позволяет откатить только собственные изменения и никогда не чистить сцену по
широкому package/component match.

## 14. Launch overlay

1. Overlay создаёт только явный launcher entry до открытия ADB и первой
   мутации.
2. На одну логическую `OPEN` приходится один lease. Повторные тапы подключаются
   к той же операции.
3. Overlay имеет нативно выглядящий фон/индикатор и сразу объясняет:
   «Запускаю разделение экрана».
4. Минимальное время показа допустимо для устранения вспышки, но overlay
   закрывается только после подтверждённой интерактивной сцены либо ошибки.
5. Hard deadline одновременно скрывает окно, переводит операцию в `CANCELLED`
   и запрещает её поздние мутации.
6. После deadline touch shield гарантированно удалён. Бесконечный input block
   невозможен.
7. Если overlay permission отсутствует, нужен видимый in-app/system fallback;
   молчаливое отсутствие обратной связи недопустимо.
8. Overlay не создаётся при toggle, process init, picker resume, divider,
   edge-drag, Home, Back и navigation return.
9. Stale callback старой операции не закрывает overlay новой операции.

## 15. Persisted state

Атомарно сохраняются:

- `featureEnabled`;
- для каждой логической панели: `CLOSED`, `PICKER` или `APP(package)`;
- revision последней полностью завершённой операции;
- ownership и исходные значения глобальных lease.

Не сохраняются как долговечная истина:

- task ID и root ID;
- текущая физическая wide/narrow сторона;
- `OPEN`, `ATTACHING`, `RECONCILE`;
- overlay lease;
- navigation projection;
- accessibility/lifecycle hints.

Правила записи:

- Home не очищает slots.
- Последний Back/caption close: `APP -> PICKER`.
- Pane close/collapse: `... -> CLOSED`.
- Uninstall: `APP -> PICKER`.
- Restore фиксируется только после полного live read-back.
- Два одинаковых package сохраняются независимо.
- Один атомарный snapshot является источником истины; отдельное хранилище
  «последней пары» не может коммититься независимо от автомата.

## 16. Алгоритм одной операции

Вход:

- явное событие пользователя или passive hint;
- текущий persisted semantic snapshot;
- один консистентный live topology snapshot;
- ownership ledger;
- feature/navigation state.

Выход:

- новый semantic snapshot либо прежний snapshot;
- подтверждённая stable live scene;
- конечный статус операции и сообщение пользователю;
- освобождённый overlay/lease.

Порядок:

1. Назначить `operationId`, priority, deadline и cancellation token.
2. Сделать read-only live snapshot.
3. Проверить feature state, ownership и предусловия.
4. Построить полный plan без мутаций.
5. Выполнять exact mutations по одной, проверяя token перед и после каждой.
6. После каждой мутации вести rollback journal.
7. Сделать final live read-back и проверить продуктовый postcondition.
8. Одним атомарным commit сохранить semantic snapshot.
9. Освободить overlay/lease и сообщить результат.
10. При ошибке/timeout/cancel прекратить новые мутации, выполнить exact rollback
    или принять только полностью доказанную stable scene.

Passive hint не запускает этот полный изменяющий алгоритм автоматически. Он
лишь помечает live snapshot устаревшим и просит actor сверить последний экран,
когда нет более приоритетной операции.

## 17. Входы и выходы текущего кода

Эта таблица не является желаемой архитектурой; она фиксирует точки, которые
нужно свести к алгоритму выше.

| Вход | Текущая точка | Требуемый выход |
|---|---|---|
| Инициализация процесса | `DenzaAppRepository.startAdbRuntime -> SplitScreenCoordinator.initialize` | Read-only init; при `DISABLED` никаких cleanup/task/gate команд. |
| Toggle | `SplitScreenToggleController -> SplitScreenCoordinator.setEnabled` | Синхронная смена semantic feature state; bounded подготовка/cleanup с rollback. |
| Иконка Split Screen | `SplitLauncherEntryActivity -> openPickerSession` | Одна cancellable `OPEN` с overlay и postcondition. |
| Тап в picker | `SplitPickerActivity -> SplitCommandProvider -> selectApp` | Одна pane-scoped `SELECT`; timeout возвращает picker в interactive state. |
| Picker resume/stop | `onPickerVisible/onPickerHidden` | Hint; close принимается только по live topology, не по lifecycle. |
| Stock picker | accessibility service -> `onNativePickerVisible` | Обработка только после подтверждённого edge commit. |
| Любой windows changed | accessibility service -> `onDividerResized` | События отфильтрованы/сконфлированы; один свежий read-only snapshot. |
| Home | accessibility service -> `onHomeVisible` | Приоритетный синхронно ограждённый gate suspend до следующего app launch. |
| Navigation | `NavigationCoordinator -> prepare/completeNavigationReturn` | Один shared operation protocol и ownership lease. |

## 18. Адверсариальные acceptance-сценарии

Каждый сценарий проверяет не только видимый экран, но и task ID/PID, package,
root, persisted semantic state, overlay, gate/lease и playback.

1. `Music | Yandex` -> Home -> сразу Yandex: только Yandex fullscreen; Music и
   ADAS не появляются рядом.
2. `Music | Yandex` -> Home -> Split Screen: возвращаются те же живые task;
   музыка не перезапускается и продолжает играть.
3. `Music | Yandex` -> десять быстрых resize: те же task/PID, ноль restart,
   итоговые pane соответствуют последнему snapshot.
4. `FULL(Music)` -> edge-drag -> cancel: точный исходный Music task fullscreen,
   ни overlay, ни новый picker.
5. `FULL(Music)` -> edge-drag -> commit: тот же Music + один picker.
6. Edge-drag -> во время stock picker выбрать приложение: выбранное приложение
   не удаляется поздней Denza-командой.
7. Тап Split Screen -> сразу Home: Home остаётся; через 15 секунд ничего
   внезапно не открывается.
8. Тап Split Screen -> выключить toggle: операция отменена; поздних task moves
   нет; overlay снят.
9. Тап Split Screen при отключённом ADB: прежний экран, видимая ошибка,
   overlay снят, persisted state прежний.
10. Оборвать ADB после первого созданного picker: нет half-open gate и
    случайного второго приложения; результат rollback либо проверенная
    частичная сцена с видимой ошибкой.
11. Два быстрых тапа иконки: один набор task и один итог.
12. Выбрать один package в обеих панелях: два независимых task либо безопасный
    отказ второй попытки без перемещения первого.
13. Выбрать Denza Apps: обычное приложение в pane; открыть/закрыть его; сосед и
    split не перестраиваются.
14. Запустить Denza Apps при выключенном toggle поверх любого OEM split: OEM
    scene побайтно/по task topology не меняется.
15. Закрыть Music последним Back -> picker -> Home -> Split Screen: Music не
    воскресает.
16. Закрыть одну пустую pane: второй picker fullscreen; закрыть его: Home.
17. Nav -> cluster, выбрать Temp в vacancy, сменить Music на Radio, вернуть Nav:
    exact Temp закрыт, Nav возвращён, Radio сохранено и не перезапущено.
18. Nav -> cluster, закрыть исходную pane, вернуть Nav: Nav fullscreen;
    companion не воскрешается.
19. Убить процесс Denza Apps при `Music | Yandex`: оба приложения продолжают
    работать; cold init не двигает их.
20. Перезагрузить IVI: старые numeric task IDs не используются; до явного тапа
    приложения не запускаются.
21. Одновременно активировать Simulcast и Split: пока совместный ownership-
    контракт не доказан, вторая функция fail closed с понятным сообщением и не
    двигает задачи.
22. 100 `TYPE_WINDOWS_CHANGED` во время `SELECT`: selection не ждёт очередь из
    100 reconcile и не применяется поверх более нового Home.

## 19. Релевантность стороннего ревью на текущем snapshot

| № | Вердикт | Причина |
|---|---|---|
| 1. Закрытое приложение иногда возвращается | Частично актуально | Конкретный старый путь теперь синхронизирует пару по picker/collapse, но два раздельных persisted store и асинхронные окна отказа всё ещё допускают расхождение. Наблюдение `Music -> Home -> ADAS` требует считать инвариант нарушенным до live-доказательства. |
| 2. Пустой picker fullscreen | Не дефект | Пользовательское решение: survivor, включая picker, остаётся fullscreen. Финальный close ведёт Home. |
| 3. Back оставляет прозрачный host | Устарело для текущего happy path | Текущий picker запускает приложения напрямую; старый host остаётся в коде/manifest и должен быть удалён, но не является штатным путём. |
| 4. Возврат всегда перезапускает приложения | Формулировка устарела, риск актуален | Специальная пересборка через Denza Back больше не должна быть контрактом, однако current restore всё ещё может создать новую задачу при недоказанном adoption. Нужен task/PID acceptance. |
| 5. Vacancy навигации — ловушка | Принятое поведение | Временное приложение закрывается при возврате Nav. Это надо явно показать/описать и удалять только по exact identity. |
| 6. Только особый Back из Denza Apps восстанавливает split | Старый механизм устарел; текущий дефект сильнее | Denza Apps теперь должна быть обычным app task, но её process init при `DISABLED` способен запустить глобальный cleanup. |
| 7. Нет сообщения о частичном restore | Исправлено в коде, не принято live | Notice теперь публикуется в picker; проверить на машине всё ещё нужно. |
| 8. Stock picker можно успеть выбрать | Частично актуально | Добавлено ожидание commit/pointer release, но нет operation fencing; выбор в stock picker должен пройти отдельный live acceptance. |
| 9. Каталог застывает и запрещён duplicate | В основном исправлено | Каталог обновляется при start/package events. Blanket package-ban снят; для `singleTask/singleInstance` остаётся технический безопасный отказ вместо гарантии второго task. |
| 10. Иконка молчит | Визуальная часть исправлена, cancellation нет | Overlay появился и bounded визуально, но 15-секундный timer не отменяет продолжающуюся операцию. |
| 11. ADB, duplicate, Simulcast | ADB и Simulcast актуальны | У ADB-операций нет общего cancellation/rollback; совместное владение задачами с Simulcast не доказано. Duplicate требует описанного best-effort контракта. |

## 20. Сверка acceptance-сценариев с текущей реализацией

Вердикты ниже относятся к exact snapshot установленной версии: base
`a1ff965f3e48cc9247a7b02c37af4665bb09022c` плюс вошедшие в APK локальные
изменения. Это code-only проверка; `частично` не означает live acceptance.

| Сценарий из раздела 18 | Code-only вердикт | Почему |
|---|---|---|
| 1. Home -> сразу Yandex без ADAS | Не соответствует | Home обрабатывается асинхронно в общей очереди. К моменту shell-проверки на экране уже может быть Yandex, событие Home теряется, а OEM gate остаётся открыт. |
| 2. Home -> Split возвращает те же Music/Nav task | Не доказано | Есть adoption живой owned scene, но при несовпадении нескольких snapshot код перестраивает сцену и может cold-launch приложения. Coordinator-сценария и проверки PID нет. |
| 3. Серия resize без restart/playback change | Частично | Есть settlement и identity checks, но каждый windows event конкурирует в общей очереди; нет сценарного теста и live-доказательства. |
| 4. Edge-drag cancel ничего не меняет | Частично | До commit есть ожидание pointer release, но у ожидания нет operation generation; старый callback может продолжить работу уже на новой сцене. |
| 5. Edge commit сохраняет fullscreen task | Частично | Shell path пытается принять survivor и добавить picker, но coordinator/event race не тестируется. |
| 6. Выбор в stock picker не удаляется Denza | Не доказано | Между commit detection, live snapshot и attach нет транзакции; поздняя замена picker остаётся возможной. |
| 7. Split tap -> Home, без позднего открытия | Не соответствует | `openPickerSession()` не отменяется Home и продолжает мутации после queued Home-события. |
| 8. Split tap -> toggle off, без позднего открытия | Не соответствует | `generation` ограждает start/stop, но не шаги уже запущенного `openPickerSession()`; cleanup также ждёт ту же очередь. |
| 9. ADB недоступен до первой мутации | Частично | Ошибка и закрытие overlay есть, но только если сбой действительно предшествует всем глобальным/задачным изменениям. |
| 10. ADB оборван посередине | Не соответствует | Catch публикует `ERROR`, но не откатывает уже изменённые lease/gate/tasks. |
| 11. Два тапа иконки — одна операция | Частично | `pickerOpenInFlight` не даёт второй task mutation, но второй запрос получает преждевременный success и отдельный overlay lease вместо результата общей операции. |
| 12. Один package в двух pane | Частично | Обычный launch mode разрешён. `singleTask/singleInstance` безопасно отвергается заранее; независимость и сохранение первого task требуют live-проверки. |
| 13. Denza Apps как обычное приложение | Не соответствует | Picker и Main имеют один package, discovery не всегда различает component/task; process init сам запускает split coordinator. |
| 14. Denza Apps при toggle off не трогает OEM split | Не соответствует, критично | `initialize(false) -> stopAsync() -> closePickers()` захватывает stock picker и безусловно закрывает global gate. |
| 15. Закрытая Music не воскресает | Частично | Picker-visible/collapse теперь синхронизируют пару, но automaton и last-pair коммитятся раздельно; lifecycle/очередь не тестируются. |
| 16. Один picker fullscreen, финальный close -> Home | Частично | Reducer и shell collapse path есть, но нет coordinator/instrumentation acceptance на прошивке. |
| 17. Nav return закрывает Temp, сохраняет новый companion | Частично | Фактический return удаляет exact displaced task и перечитывает scene, но существует второй несовместимый projection reducer path и гонка двух executor. |
| 18. Nav return после collapse | Частично | Fullscreen plan предусмотрен, но process death/новая topology и реальная прошивка не проверены end-to-end. |
| 19. Process death Denza не двигает third-party tasks | Не соответствует | Cold init выполняет stop либо глобальную подготовку и загружает persisted task identity. |
| 20. Reboot не доверяет старым task ID | Не соответствует | Automaton сохраняет host/app task ID без boot epoch; фоновые hints могут использовать их до явного rebuild. |
| 21. Split + Simulcast fail closed | Не доказано/не соответствует | Единого ownership protocol или явной взаимной блокировки нет. |
| 22. 100 windows events не задерживают SELECT/Home | Не соответствует | Все пути используют один blocking executor; reconcile ставится до фильтра package/class и coalescing по последнему snapshot отсутствует. |

Локальный прогон exact snapshot:

```text
./gradlew :denza-apps:testDebugUnitTest --tests 'dev.denza.apps.feature.split.*'
156 tests, 0 failures, 0 errors
```

Этот зелёный результат не опровергает таблицу: теста
`SplitScreenCoordinator` нет, `androidTest` source set отсутствует, а 41 из 156
тестов проверяет retired `SplitRoutingReducer`/`SplitShellRouter`. Существующие
тесты хорошо проверяют отдельные reducer/shell-функции, но не порядок реальных
событий, cancellation, общий executor, process init, Home и task/PID на машине.

## 21. Текущие архитектурные расхождения

До добавления новых точечных фиксов нужно устранить причины, а не расширять
набор специальных веток.

1. **Критично: destructive init при выключенной функции.**
   `SplitScreenCoordinator.initialize()` вызывает `stopAsync()`, а cleanup
   ищет также stock `SplitScreenListActivity` и безусловно закрывает глобальный
   gate. Обычный старт Denza Apps способен затронуть не принадлежащую продукту
   OEM-сцену.
2. **Критично: overlay deadline не отменяет работу.** Overlay освобождает только
   окно; `openPickerSession()` без cancellation token продолжает ADB/move/
   restore и может поздно изменить экран.
3. **Критично: операции не транзакционны.** Gate, accessibility, resizeability,
   picker и приложения меняются по шагам; catch не возвращает уже изменённые
   части к исходному snapshot.
4. **Высоко: один блокирующий executor для всех событий.** Edge wait может
   занимать около 15 секунд, divider reconcile — дополнительные ожидания; Home,
   selection и launch стоят за старыми passive events.
5. **Высоко: accessibility storm.** Каждый `TYPE_WINDOWS_CHANGED` до фильтрации
   пытается запустить divider reconcile; in-flight flags выбрасывают новые
   hints, а старая queued job потом работает уже с другой сценой.
6. **Высоко: два неатомарных источника persisted truth.** Automaton с task ID и
   last-pair package сохраняются отдельно и в разном порядке.
7. **Высоко: task/root ID переживают process/reboot без epoch.** Повторно
   использованный numeric ID может быть принят как старая owned task.
8. **Высоко: Denza Apps self-client неоднозначен.** Picker и обычное приложение
   имеют один package; часть проверок опирается на package, а process init сам
   запускает coordinator.
9. **Высоко: navigation имеет два жизненных цикла.** Reducer path
   `ProjectionReturned` практически недостижим после текущего
   `ProjectionStarted`, а фактический return идёт отдельным synchronous path и
   удаляет displaced tasks.
10. **Средне: в production tree живут несколько поколений архитектуры.** При
    hardcoded explicit mode остаются router/reducer, exported `SplitAppHost`,
    placeholder, старые launch/cleanup/classification-ветки и их тесты.
11. **Средне: тесты проверяют детали, но не координатор.** В split около 156
    unit-тестов и более пяти тысяч строк test code, но нет coordinator/
    instrumentation/e2e-проверок очереди событий, cancellation, task identity,
    Home и реальной прошивки.

## 22. Направление очистки перед реализацией

1. Оставить один operation actor с явным priority, generation, deadline и
   cancellation вместо общей очереди блокирующих callbacks.
2. Ввести единый semantic snapshot и атомарный store без persisted task/root ID.
3. Ввести ownership ledger и mutation journal для exact rollback.
4. Сделать shell boundary возвращающим типизированный live snapshot и
   проверяемые postconditions; не принимать решения по нескольким несвязанным
   `am stack list` без проверки generation.
5. Превратить accessibility/lifecycle в coalesced hints, а не источники
   автоматических мутаций.
6. Удалить retired router/host/placeholder и тесты старой архитектуры после
   отдельной проверки миграции старых задач.
7. Сначала написать scenario-level coordinator tests по разделу 18, затем
   минимальные reducer/shell tests для реально используемого пути.
8. Проверять на машине по одному сценарию: зафиксировать before task/root/PID/
   playback, выполнить один ввод, зафиксировать after и сравнить с контрактом.

## 23. Definition of Done

Split Screen нельзя считать надёжным только по зелёным unit-тестам. Готовность
есть, когда:

- все сценарии раздела 18 проходят на целевой прошивке;
- нет поздних мутаций после Home, toggle off или timeout;
- Denza Apps при `DISABLED` не меняет OEM split;
- same-package и Denza Apps проверены как отдельные app task;
- task/PID живых Music/Nav сохраняются при Home, resize и повторном open;
- закрытые приложения не воскресают;
- overlay всегда освобождает input и отменяет операцию;
- reboot/update не доверяют старым task ID;
- Split и Navigation используют единый ownership protocol;
- Simulcast либо доказан совместимым, либо fail closed;
- production tree содержит только одну активную split-архитектуру.
