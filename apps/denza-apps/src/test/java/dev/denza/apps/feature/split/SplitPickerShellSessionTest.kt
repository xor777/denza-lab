package dev.denza.apps.feature.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitPickerShellSessionTest {
    @Test
    fun nativePickerReplacementWaitsForCommittedSplitAndReleasedDivider() {
        var areaReads = 0
        var inputReads = 0
        var pauses = 0
        val session = SplitPickerShellSession(
            shell = { command ->
                when (command) {
                    "service call activity_task 30" -> {
                        areaReads += 1
                        intParcel(if (areaReads < 2) 2 else 3)
                    }
                    "dumpsys input" -> {
                        inputReads += 1
                        if (inputReads == 1) ACTIVE_DIVIDER_TOUCH else NO_ACTIVE_TOUCH
                    }
                    else -> error("Unexpected command: $command")
                }
            },
            apkPath = "/tmp/denza-apps.apk",
            settle = { pauses += 1 },
        )

        assertTrue(session.awaitNativePickerCommit())
        assertEquals(11, areaReads)
        assertEquals(11, inputReads)
        assertEquals(10, pauses)
    }

    @Test
    fun cancelledNativePickerDragRejectsTransientBalancedAreaAfterRelease() {
        var areaReads = 0
        var inputReads = 0
        val session = SplitPickerShellSession(
            shell = { command ->
                when (command) {
                    "service call activity_task 30" -> {
                        areaReads += 1
                        intParcel(if (areaReads <= 8) 3 else 2)
                    }
                    "dumpsys input" -> {
                        inputReads += 1
                        if (inputReads == 1) ACTIVE_DIVIDER_TOUCH else NO_ACTIVE_TOUCH
                    }
                    else -> error("Unexpected command: $command")
                }
            },
            apkPath = "/tmp/denza-apps.apk",
            settle = {},
        )

        assertFalse(session.awaitNativePickerCommit())
        assertEquals(13, areaReads)
        assertEquals(13, inputReads)
    }

    @Test
    fun cancelledNativePickerDragDoesNotStartReplacement() {
        var reads = 0
        var pauses = 0
        val session = SplitPickerShellSession(
            shell = { command ->
                when (command) {
                    "service call activity_task 30" -> {
                        reads += 1
                        intParcel(2)
                    }
                    "dumpsys input" -> NO_ACTIVE_TOUCH
                    else -> error("Unexpected command: $command")
                }
            },
            apkPath = "/tmp/denza-apps.apk",
            settle = { pauses += 1 },
        )

        assertFalse(session.awaitNativePickerCommit())
        assertEquals(5, reads)
        assertEquals(reads - 1, pauses)
    }

    @Test
    fun activeDividerCommitWaitIsBoundedWithoutMutation() {
        var areaReads = 0
        var inputReads = 0
        var pauses = 0
        val session = SplitPickerShellSession(
            shell = { command ->
                when (command) {
                    "service call activity_task 30" -> {
                        areaReads += 1
                        intParcel(3)
                    }
                    "dumpsys input" -> {
                        inputReads += 1
                        ACTIVE_DIVIDER_TOUCH
                    }
                    else -> error("Unexpected command: $command")
                }
            },
            apkPath = "/tmp/denza-apps.apk",
            settle = { pauses += 1 },
        )

        assertFalse(session.awaitNativePickerCommit())
        assertEquals(150, areaReads)
        assertEquals(150, inputReads)
        assertEquals(149, pauses)
    }

    @Test
    fun nativePickerMutationIsRejectedWhenAreaChangesAfterCommitWait() {
        var areaReads = 0
        var inputReads = 0
        val session = SplitPickerShellSession(
            shell = { command ->
                when (command) {
                    "service call activity_task 30" -> {
                        areaReads += 1
                        intParcel(if (areaReads <= 10) 3 else 2)
                    }
                    "dumpsys input" -> {
                        inputReads += 1
                        NO_ACTIVE_TOUCH
                    }
                    else -> error("Unexpected command: $command")
                }
            },
            apkPath = "/tmp/denza-apps.apk",
            settle = {},
        )

        assertTrue(session.awaitNativePickerCommit())
        assertFalse(session.nativePickerMutationAllowed())
        assertEquals(11, areaReads)
        assertEquals(10, inputReads)
    }

    @Test
    fun panesHaveStableOpposites() {
        assertEquals(SplitPane.SECONDARY, SplitPane.PRIMARY.other())
        assertEquals(SplitPane.PRIMARY, SplitPane.SECONDARY.other())
    }

    /**
     * Правка W3 волны 8 (инвариант 3, примечание контракта под 1.5; диагноз v23 Д1(б)): чужая
     * задача в панельном корне - задача пользователя. Расчищая корень под сцену, сборка выселяет
     * её живой в полноэкранный root фоном - live-proven `am stack move-task ... false` - и не
     * шлёт ни одного remove-task. До волны 8 этот же мир кончался казнью обеих задач (живьём:
     * музыка 332 удалена при пустых targets).
     */
    @Test
    fun explicitOpenCreatesTwoPickerBasesAndEvictsOldPaneTasksAlive() {
        val fake = FakeShell().apply {
            addTask(PRIMARY_ROOT, 40, NAVIGATOR, "$NAVIGATOR.MainActivity")
            addTask(SECONDARY_ROOT, 41, MUSIC, "$MUSIC.MainActivity")
        }
        val split = session(fake)

        split.buildPickers()

        assertEquals(PRIMARY_PICKER_ACTIVITY, fake.topActivity(PRIMARY_ROOT))
        assertEquals(SECONDARY_PICKER_ACTIVITY, fake.topActivity(SECONDARY_ROOT))
        assertFalse(fake.hasPackage(PRIMARY_ROOT, NAVIGATOR))
        assertFalse(fake.hasPackage(SECONDARY_ROOT, MUSIC))
        assertEquals(3, fake.area)
        assertTrue("задача пользователя жива", fake.hasTask(40))
        assertTrue(fake.hasTask(41))
        assertEquals("выселена фоном в полноэкранный root", FULL_ROOT, fake.taskRoot(40))
        assertEquals(FULL_ROOT, fake.taskRoot(41))
        assertTrue(fake.commands.any { it == "am stack move-task 40 $FULL_ROOT false" })
        assertTrue(fake.commands.any { it == "am stack move-task 41 $FULL_ROOT false" })
        assertFalse(
            "ни одного remove-task: членство в корне - не приговор",
            fake.commands.any { it.contains(" remove-task ") },
        )
        assertFalse(fake.commands.any { it == "service call activity_task 115" })
    }

    /**
     * Обратная сторона правки W3: своё по точному компоненту sweep по-прежнему удаляет. Третья
     * собственная пикер-база, оставшаяся в корне после того, как обе панели получили своих,
     * казнится, а не выселяется - её identity и есть наш компонент (инвариант 3).
     */
    @Test
    fun aStaleThirdPickerBaseOfOurOwnIsStillRemovedByTheSweep() {
        val fake = FakeShell().apply {
            addTask(PRIMARY_ROOT, 40, SPLIT_HOST_PACKAGE, PRIMARY_PICKER_ACTIVITY)
            addTask(PRIMARY_ROOT, 41, SPLIT_HOST_PACKAGE, PRIMARY_PICKER_ACTIVITY)
            addTask(SECONDARY_ROOT, 42, SPLIT_HOST_PACKAGE, SECONDARY_PICKER_ACTIVITY)
        }

        val hosts = session(fake).buildPickers()

        assertEquals("усыновлена старшая база панели", 41, hosts.getValue(SplitPane.PRIMARY))
        assertEquals(42, hosts.getValue(SplitPane.SECONDARY))
        assertFalse("лишняя собственная база удалена, не выселена", fake.hasTask(40))
        assertTrue(fake.commands.any { it.contains(" remove-task ") && it.contains("remove-task 40 ") })
        assertFalse(fake.commands.any { it == "am stack move-task 40 $FULL_ROOT false" })
    }

    /**
     * Правка W3 волны 8 (диагноз v23 Д2, U3): нативно втянутый хаб - наш package, но НЕ наш
     * компонент - это задача пользователя. Sweep сборки выселяет её живой фоном; пара строится,
     * и ни один remove-task её не адресует.
     */
    @Test
    fun aNativelyPulledHubTaskIsEvictedAliveWhileThePairBuilds() {
        val fake = FakeShell().apply {
            area = 3
            addTask(PRIMARY_ROOT, 344, SPLIT_HOST_PACKAGE, "$SPLIT_HOST_PACKAGE.MainActivity")
        }

        val built = session(fake).buildScene(
            PICKERS,
            mapOf(
                SplitPane.PRIMARY to launchTargetOf(NAVIGATOR),
                SplitPane.SECONDARY to launchTargetOf(MUSIC),
            ),
        )

        assertEquals(emptySet<SplitPane>(), built.failed)
        assertEquals(NAVIGATOR, built.panes.getValue(SplitPane.PRIMARY).appPackageName)
        assertEquals(MUSIC, built.panes.getValue(SplitPane.SECONDARY).appPackageName)
        assertTrue("задача хаба жива", fake.hasTask(344))
        assertEquals("выселена фоном", FULL_ROOT, fake.taskRoot(344))
        assertTrue(fake.commands.any { it == "am stack move-task 344 $FULL_ROOT false" })
        assertFalse(fake.commands.any { it.contains("remove-task 344 ") })
    }

    /**
     * Геометрия выселенной задачи - дело прошивки (машинная правда 2026-08-25, чтение машины).
     *
     * Полноэкранный IVI-корень фоновых задач не держит вовсе, а всякая фоновая задача живёт в
     * собственном корне, чьи границы равны её собственным, - и побывавшая в панели, и не бывавшая.
     * Выселение обязано состояться и не обязано ничего перекраивать: продукт переносит задачу
     * живой и ни на чём не спотыкается.
     */
    @Test
    fun anEvictedTaskLeavesThePaneAliveAndKeepsWhateverGeometryTheFirmwareGivesIt() {
        val fake = FakeShell().apply {
            area = 3
            preserveBoundsOnShellMove = true
            refusePaneBoundsFor += MUSIC
            addTask(SECONDARY_ROOT, 41, MUSIC, "$MUSIC.MainActivity")
        }

        val hosts = session(fake).buildPickers()

        assertEquals(2, hosts.size)
        assertTrue("задача пользователя жива", fake.hasTask(41))
        assertEquals("и выселена", FULL_ROOT, fake.taskRoot(41))
        assertEquals(
            "границы остались прежними, и это никого не остановило",
            SECONDARY_BOUNDS,
            fake.taskBounds(41),
        )
    }

    /**
     * Тот же класс слепоты в pre-clear тапа (правка W3 волны 8): чужой житель корня выселяется
     * живым, а удаляется только собственный артефакт по точному компоненту - transparent host
     * прерванного запуска.
     */
    @Test
    fun aPickerTapEvictsTheForeignOccupantAndRemovesOnlyOurOwnArtifact() {
        val fake = FakeShell()
        val split = session(fake)
        val pickers = split.buildPickers()
        fake.addTask(SECONDARY_ROOT, 344, SPLIT_HOST_PACKAGE, "$SPLIT_HOST_PACKAGE.MainActivity")
        fake.addTask(SECONDARY_ROOT, 345, SPLIT_HOST_PACKAGE, SPLIT_APP_HOST_ACTIVITY)

        val placement = split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )

        assertEquals(SplitPane.SECONDARY, placement.pane)
        assertEquals(MUSIC, placement.packageName)
        assertTrue("чужой житель корня жив", fake.hasTask(344))
        assertEquals(FULL_ROOT, fake.taskRoot(344))
        assertTrue(fake.commands.any { it == "am stack move-task 344 $FULL_ROOT false" })
        assertFalse("собственный host-артефакт удалён по exact компоненту", fake.hasTask(345))
        assertFalse(fake.commands.any { it.contains("remove-task 344 ") })
    }

    @Test
    fun liveFirmwarePickerPlusBootstrapAppBecomesTwoPickerBases() {
        val fake = FakeShell(secondaryBootstrapPackage = STOCK_BOOTSTRAP_PACKAGE)

        val hosts = session(fake).buildPickers()

        assertEquals(PRIMARY_PICKER_ACTIVITY, fake.topActivity(PRIMARY_ROOT))
        assertEquals(SECONDARY_PICKER_ACTIVITY, fake.topActivity(SECONDARY_ROOT))
        assertFalse(fake.hasPackage(SECONDARY_ROOT, NAVIGATOR))
        assertEquals(1, fake.taskCount(PRIMARY_ROOT))
        assertEquals(1, fake.taskCount(SECONDARY_ROOT))
        assertEquals(2, hosts.values.toSet().size)
        assertTrue(fake.hasActivity(PRIMARY_ROOT, PRIMARY_PICKER_ACTIVITY))
        assertTrue(fake.hasActivity(SECONDARY_ROOT, SECONDARY_PICKER_ACTIVITY))
        assertFalse(fake.commands.any { it.contains("replace-task-base ") })
        assertTrue(
            fake.commands.any { command ->
                command.startsWith("am start ") &&
                    command.contains("byd.intent.category.START_IVI_PRIMARY") &&
                    command.contains(PRIMARY_PICKER) &&
                    command.endsWith("-f 0x18010000")
            },
        )
        assertTrue(
            fake.commands.any { command ->
                command.startsWith("am start ") &&
                    command.contains("byd.intent.category.START_IVI_SECOND") &&
                    command.contains(SECONDARY_PICKER) &&
                    command.endsWith("-f 0x18010000")
            },
        )
        assertFalse(fake.commands.any { it.contains("SplitTaskProxyMain start-in-task ") })
        assertFalse(fake.commands.any { it == "am stack move-task 100 $PRIMARY_ROOT true" })
        assertFalse(fake.commands.any { it == "am stack move-task 101 $SECONDARY_ROOT true" })
    }

    @Test
    fun explicitOpenPlacesPickersInNativePaneRootsWithoutSyntheticDividerDrag() {
        val fake = FakeShell().apply {
            addTask(PRIMARY_ROOT, 40, NAVIGATOR, "$NAVIGATOR.MainActivity")
            addTask(SECONDARY_ROOT, 41, MUSIC, "$MUSIC.MainActivity")
        }

        session(fake).buildPickers()

        assertFalse(fake.commands.any { it == "service call activity_task 115" })
        SplitPane.entries.forEach { pane ->
            val root = if (pane == SplitPane.PRIMARY) PRIMARY_ROOT else SECONDARY_ROOT
            val picker = PICKERS.getValue(pane)
            assertTrue(
                fake.commands.any { command ->
                    command.startsWith("am start ") &&
                        command.contains(" '$picker' ") &&
                        command.contains("byd.intent.category.START_IVI_")
                },
            )
            assertTrue(fake.hasActivity(root, picker.substringAfter('/')))
            assertEquals(1, fake.taskCount(root))
        }
        assertFalse(fake.hasPackage(PRIMARY_ROOT, STOCK_PICKER_PACKAGE))
        assertFalse(fake.commands.any { it.startsWith("input swipe ") })
        assertFalse(fake.commands.any { it.contains("SplitTaskProxyMain start-in-task ") })
        assertFalse(fake.commands.any { it.contains("replace-task-base ") })
    }

    @Test
    fun fullscreenNativeHostIsReplacedByPickerAlreadyInLiveRootBounds() {
        val fake = FakeShell(nativeBootstrapStartsFullscreen = true)

        val hosts = session(fake).buildPickers()

        assertEquals(PRIMARY_BOUNDS, fake.taskBounds(hosts.getValue(SplitPane.PRIMARY)))
        assertEquals(SECONDARY_BOUNDS, fake.taskBounds(hosts.getValue(SplitPane.SECONDARY)))
        assertFalse(fake.commands.any { it.startsWith("am task resize ") })
    }

    /**
     * Правка W4 (волна 7, контракт 1.5.3, live v22 b3): split-способный собственный пакет
     * прошивка кладёт по СВОИМ правилам стороны - тап в широком пикере, а окно фактически
     * встаёт в узкую панель поверх её пикера. Прежняя пара «слепая пауза + один снапшот
     * ВЫБРАННОГО root» объявляла ложный rollback реально вставшему окну («выбрал Denza Apps →
     * снова пикер, со второго раза открылось»). Успех - задача видимая верхняя в любой из двух
     * панелей, слот записывается по фактической стороне.
     */
    @Test
    fun aSelectionTheFirmwarePlacesIntoTheOtherPaneIsRecordedByItsActualSide() {
        val fake = FakeShell()
        val split = session(fake)
        val hosts = split.buildPickers()
        fake.firmwareChoosesRootFor[SPLIT_HOST_PACKAGE] = PRIMARY_ROOT

        val placement = split.selectApp(
            pickerTaskId = hosts.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(
                SPLIT_HOST_PACKAGE,
                "$SPLIT_HOST_PACKAGE/$SPLIT_HOST_PACKAGE.MainActivity",
            ),
            pickerComponents = PICKER_COMPONENTS,
        )

        assertEquals("слот записан по фактической стороне", SplitPane.PRIMARY, placement.pane)
        assertEquals(
            "хозяин панели - пикер фактической стороны",
            hosts.getValue(SplitPane.PRIMARY),
            placement.hostTaskId,
        )
        assertEquals(SPLIT_HOST_PACKAGE, placement.packageName)
        assertEquals(
            "окно фактически видимое верхнее в узкой панели",
            placement.appTaskId,
            fake.topTaskId(PRIMARY_ROOT),
        )
        assertEquals(
            "пикер выбранной панели не тронут и снова верхний",
            hosts.getValue(SplitPane.SECONDARY),
            fake.topTaskId(SECONDARY_ROOT),
        )
    }

    /**
     * Правка волны 13 (П3, приёмка v28, дефект 0): приложение с двумя живыми задачами вообще
     * нельзя было выбрать в панель.
     *
     * Живьём: `select outcome=rolled-back reason=Приложение ru.yandex.music не стало верхним в
     * выбранном окне in 6139ms`, воспроизведено дважды. У Яндекс.Музыки две живые задачи (t316,
     * t532), прошивка привела в панель обе, верхней стала не запущенная - и постусловие
     * `top.id == launchedTask.id` объявило откат окну, которое пользователь ВИДЕЛ открытым.
     * Слот не двигался, продукт не помнил ничего, первый же Home стирал результат. Apple Music с
     * одной задачей коммитилась штатно за 2.5 с.
     *
     * Постусловие спрашивает про ЦЕЛЕВОЕ ПРИЛОЖЕНИЕ (1.5.2, 1.5.3, U5, инвариант 9), слот пишется
     * по фактической верхней задаче.
     *
     * Правка волны 14 (приёмка v29, дефект D): вторая задача пакета не «уезжает живой в фон» -
     * она остаётся в панели. Уборка волны 13 выселяла её в полноэкранный корень 4, а корень 4
     * фоновых задач не держит вовсе (машинная правда волны 10): ВИДИМОЕ окно вставало там поверх
     * всей сцены со своими прежними панельными границами. Живьём это дало битый полуэкран и
     * `select outcome=rolled-back reason=В выбранном split-окне нет верхней задачи in 6623ms`
     * (детерминированно 2/2) - обе настоящие панели становились невидимыми, `resolvedTopTask()`
     * возвращал null. Прошивка привела эту задачу в панель сама, сверху всё равно стоит выбранное
     * приложение - панель на экране правильная, и v28 это видел собственными глазами.
     */
    @Test
    fun aSelectOfAnAppWithTwoLiveTasksCommitsTheWindowTheUserSees() {
        val fake = FakeShell()
        val split = session(fake)
        val hosts = split.buildPickers()
        // Две живые задачи пакета вне панелей - ровно то, что читал стек v28 перед тапом.
        fake.addTask(DETACHED_ROOT, 316, MUSIC, "$MUSIC.MainActivity")
        fake.addTask(DETACHED_ROOT, 532, MUSIC, "$MUSIC.MainActivity")
        fake.firmwareDragsEveryTaskOf += MUSIC

        val placement = split.selectApp(
            pickerTaskId = hosts.getValue(SplitPane.PRIMARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )

        assertEquals(SplitPane.PRIMARY, placement.pane)
        assertEquals(MUSIC, placement.packageName)
        // Запуск адресовал t532 - продукт сам её и двигал, - а прошивка положила поверх t316.
        assertTrue(
            "запуск взял и промоутил именно t532: ${fake.commands}",
            fake.commands.any { it == "am stack move-task 532 $PRIMARY_ROOT true" },
        )
        assertEquals(
            "слот записан по фактически вставшему окну, а не по адресату запуска",
            316,
            placement.appTaskId,
        )
        assertEquals(placement.appTaskId, fake.topTaskId(PRIMARY_ROOT))
        assertEquals(
            "панель - это её база и ОДНО приложение, каким бы числом задач оно ни жило",
            listOf(hosts.getValue(SplitPane.PRIMARY), 532, 316),
            fake.taskIds(PRIMARY_ROOT),
        )
        assertEquals("вторая задача пакета осталась в панели", PRIMARY_ROOT, fake.taskRoot(532))
        assertFalse(
            "и не выселена в полноэкранный корень поверх сцены: ${fake.commands}",
            fake.commands.any { it == "am stack move-task 532 $FULL_ROOT false" },
        )
        assertFalse(
            "её никто не убивал",
            fake.commands.any { it.contains("remove-task ") && it.contains(" 532 ") },
        )
        assertEquals("сцена осталась сбалансированным split", 3, fake.area)
        // U5 целиком: панель, которую только что собрал выбор, продукт обязан ещё и уметь
        // прочитать - иначе следующее открытие пересоберёт живую сцену (U2, 1.3.5).
        val read = split.readOwnedSession(PICKER_COMPONENTS)
        assertEquals("adoptable", read.reason)
        assertEquals(316, read.scene?.get(SplitPane.PRIMARY)?.appTaskId)
        assertEquals(MUSIC, read.scene?.get(SplitPane.PRIMARY)?.appPackageName)
    }

    /**
     * Обратная сторона той же правки: пакет цели больше не единственное слово постусловия, но
     * собственный компонент продукта им не становится (инвариант 3, U3).
     *
     * Дефект v17 «пикер поверх приложения» в этом мире особенно зол: пикер-база и MainActivity
     * Denza Apps живут в ОДНОМ пакете, и постусловие по одному пакету записало бы собственную
     * базу как приложение пользователя. Такая панель - не открытое приложение, а тот же пикер;
     * это отказ, а отказ оставляет базы стоять (инвариант 9, 1.5.7).
     */
    @Test
    fun aSelectRefusesWhenTheOwnPickerBaseIsWhatStaysOnTop() {
        val fake = FakeShell(pickerStaysAboveApps = true)
        val split = session(fake)
        val hosts = split.buildPickers()

        val refusal = runCatching {
            split.selectApp(
                pickerTaskId = hosts.getValue(SplitPane.PRIMARY),
                target = SplitLaunchTarget(
                    SPLIT_HOST_PACKAGE,
                    "$SPLIT_HOST_PACKAGE/$SPLIT_HOST_PACKAGE.MainActivity",
                ),
                pickerComponents = PICKER_COMPONENTS,
            )
        }.exceptionOrNull()

        assertTrue(
            "пикер-база того же пакета - не «приложение встало»: ${refusal?.message}",
            refusal?.message.orEmpty().contains("не стало верхним"),
        )
        assertTrue("база панели цела", fake.hasTask(hosts.getValue(SplitPane.PRIMARY)))
        assertTrue("и база соседа тоже", fake.hasTask(hosts.getValue(SplitPane.SECONDARY)))
    }

    /**
     * И вторая половина того же дефекта: приложение, которое теперь можно ВЫБРАТЬ, должно ещё и
     * ВОССТАНАВЛИВАТЬСЯ (1.3.2).
     *
     * До волны 13 многозадачное приложение не попадало в слот вовсе, поэтому этот путь никогда не
     * проверялся на нём живьём. Уборка панелей волны 10 покрывает его по построению - она стоит
     * до постусловия сборки, - и здесь это закреплено на той же измеренной форме мира.
     */
    @Test
    fun aRestoreOfAnAppWithTwoLiveTasksLeavesThePaneWithOneOfThem() {
        val fake = FakeShell().apply {
            area = 4
            addTask(DETACHED_ROOT, 316, MUSIC, "$MUSIC.MainActivity")
            addTask(DETACHED_ROOT, 532, MUSIC, "$MUSIC.MainActivity")
            firmwareDragsEveryTaskOf += MUSIC
        }

        val built = session(fake).buildScene(
            PICKERS,
            mapOf(SplitPane.PRIMARY to launchTargetOf(MUSIC)),
        )

        assertEquals(emptySet<SplitPane>(), built.failed)
        val app = built.panes.getValue(SplitPane.PRIMARY).appTaskId
        assertTrue("панель держит одну из живых задач пакета", app == 316 || app == 532)
        assertEquals(
            "панель - это её база и одно приложение",
            listOf(built.panes.getValue(SplitPane.PRIMARY).hostTaskId, app),
            fake.taskIds(PRIMARY_ROOT),
        )
        assertTrue("обе задачи пользователя живы", fake.hasTask(316) && fake.hasTask(532))
    }

    @Test
    fun alreadyRunningOwnedSceneIsAdoptedWithoutTaskMutation() {
        val fake = FakeShell()
        val split = session(fake)
        val hosts = split.buildPickers()
        val navigator = split.selectApp(
            pickerTaskId = hosts.getValue(SplitPane.PRIMARY),
            target = SplitLaunchTarget(NAVIGATOR, "$NAVIGATOR/$NAVIGATOR.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        fake.commands.clear()

        val read = split.readOwnedSession(PICKER_COMPONENTS)
        val existing = read.scene

        assertEquals("adoptable", read.reason)
        assertEquals(hosts.getValue(SplitPane.PRIMARY), existing?.get(SplitPane.PRIMARY)?.hostTaskId)
        assertEquals(navigator.appTaskId, existing?.get(SplitPane.PRIMARY)?.appTaskId)
        assertEquals(NAVIGATOR, existing?.get(SplitPane.PRIMARY)?.appPackageName)
        assertEquals(hosts.getValue(SplitPane.SECONDARY), existing?.get(SplitPane.SECONDARY)?.hostTaskId)
        assertEquals(null, existing?.get(SplitPane.SECONDARY)?.appTaskId)
        assertFalse(
            fake.commands.any { command ->
                command.startsWith("am start ") ||
                    command.startsWith("am stack move-task ") ||
                    command.startsWith("am task focus ") ||
                    command.contains(" remove-task ")
            },
        )
    }

    @Test
    fun dividerResizeRepairsPickerBaseStrandedAwayFromItsRecordedApp() {
        val fake = FakeShell()
        val split = session(fake)
        val hosts = split.buildPickers()
        val navigator = split.selectApp(
            pickerTaskId = hosts.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(NAVIGATOR, "$NAVIGATOR/$NAVIGATOR.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        val previous = mapOf(
            SplitPane.PRIMARY to SplitPickerObservedPane(
                hostTaskId = hosts.getValue(SplitPane.PRIMARY),
            ),
            SplitPane.SECONDARY to SplitPickerObservedPane(
                hostTaskId = hosts.getValue(SplitPane.SECONDARY),
                appTaskId = navigator.appTaskId,
                packageName = navigator.packageName,
            ),
        )

        // The live BYD resize keeps the two visible surfaces on their visual sides, but leaves
        // the hidden base of the app in the old root. The result has both picker bases together.
        fake.moveTask(navigator.appTaskId, PRIMARY_ROOT)
        fake.moveTask(hosts.getValue(SplitPane.PRIMARY), SECONDARY_ROOT)
        fake.preserveBoundsOnShellMove = true
        fake.commands.clear()

        val repaired = split.reconcileDividerResize(PICKER_COMPONENTS, previous)

        assertEquals(
            hosts.getValue(SplitPane.SECONDARY),
            repaired?.get(SplitPane.PRIMARY)?.hostTaskId,
        )
        assertEquals(navigator.appTaskId, repaired?.get(SplitPane.PRIMARY)?.appTaskId)
        assertEquals(
            hosts.getValue(SplitPane.PRIMARY),
            repaired?.get(SplitPane.SECONDARY)?.hostTaskId,
        )
        assertEquals(null, repaired?.get(SplitPane.SECONDARY)?.appTaskId)
        assertTrue(
            fake.commands.any { command ->
                command == "am stack move-task ${hosts.getValue(SplitPane.SECONDARY)} " +
                    "$PRIMARY_ROOT false"
            },
        )
        assertTrue(
            fake.commands.any { command ->
                command.startsWith(
                    "am task resize ${hosts.getValue(SplitPane.SECONDARY)} ",
                )
            },
        )
    }

    @Test
    fun transientResizeWithBothPickerBasesInOneRootEmitsNoVisiblePickerObservation() {
        val fake = FakeShell()
        val split = session(fake)
        val hosts = split.buildPickers()
        val navigator = split.selectApp(
            pickerTaskId = hosts.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(NAVIGATOR, "$NAVIGATOR/$NAVIGATOR.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )

        // Live BYD resize transient: the visible app is alone in one native root while both
        // permanent picker bases temporarily share the other. Treating the top picker as a
        // settled reveal would overwrite the still-live APP slot before resize repair runs.
        fake.moveTask(navigator.appTaskId, PRIMARY_ROOT)
        fake.moveTask(hosts.getValue(SplitPane.PRIMARY), SECONDARY_ROOT)

        assertEquals(
            null,
            split.observePickerTask(
                hosts.getValue(SplitPane.PRIMARY),
                PICKER_COMPONENTS,
            ),
        )
    }

    @Test
    fun dividerResizeDoesNotGuessOwnershipWithoutARecordedApp() {
        val fake = FakeShell()
        val split = session(fake)
        val hosts = split.buildPickers()
        val previous = SplitPane.entries.associateWith { pane ->
            SplitPickerObservedPane(hostTaskId = hosts.getValue(pane))
        }
        fake.moveTask(hosts.getValue(SplitPane.PRIMARY), SECONDARY_ROOT)
        fake.commands.clear()

        val repaired = split.reconcileDividerResize(PICKER_COMPONENTS, previous)

        assertEquals(null, repaired)
        assertFalse(fake.commands.any { command -> command.startsWith("am stack move-task ") })
        assertFalse(fake.commands.any { command -> command.startsWith("am task resize ") })
    }

    @Test
    fun coveredSceneMembershipIsProvedByExactIdentityAcrossTheWholeMainDisplay() {
        // Инвариант 5 (ред. 2026-08-24): отвязанный прошивкой член накрытой сцены жив где угодно
        // на main display; проверка существования не ограничена панельными корнями и считает
        // мёртвым только реально умершую задачу.
        val fake = FakeShell().apply { liveProductScene(withApps = true) }
        val split = session(fake)
        val scene = mapOf(
            SplitPane.PRIMARY to SplitPickerLivePane(
                pane = SplitPane.PRIMARY,
                hostTaskId = PRIMARY_PICKER_TASK,
                appTaskId = PRIMARY_APP_TASK,
                appPackageName = NAVIGATOR,
            ),
            SplitPane.SECONDARY to SplitPickerLivePane(
                pane = SplitPane.SECONDARY,
                hostTaskId = SECONDARY_PICKER_TASK,
                appTaskId = SECONDARY_APP_TASK,
                appPackageName = MUSIC,
            ),
        )

        assertFalse("живой split не накрыт", split.sceneCovered())
        fake.area = 0
        assertTrue("Home накрывает сцену", split.sceneCovered())

        // Прошивка опустошила корень сфокусированной панели: члены отвязаны, но живы.
        fake.detachTask(PRIMARY_PICKER_TASK)
        fake.detachTask(PRIMARY_APP_TASK)
        assertTrue(split.allRecordedMembersAlive(scene, PICKER_COMPONENTS))

        // Мёртвый член - нативный конец, и проверка обязана его увидеть.
        fake.removeActivity(DETACHED_ROOT, PRIMARY_PICKER_ACTIVITY)
        assertFalse(split.allRecordedMembersAlive(scene, PICKER_COMPONENTS))
    }

    /**
     * Правка W1 волны 8 (диагноз v23 Д1(а)): якорное чтение приложений различает «база утрачена»
     * и конец сцены. Выселенная Home-ом база умерла (механизм М2), её приложение отвязано живым:
     * члены не все живы, но каждый записанный app жив по exact identity на всём main display -
     * и наоборот, смерть записанного приложения якорь видит.
     */
    @Test
    fun deadPickerBaseWithLivingRecordedAppsIsABaseLossNotASceneEnd() {
        val fake = FakeShell().apply { liveProductScene(withApps = true) }
        val split = session(fake)
        val scene = mapOf(
            SplitPane.PRIMARY to SplitPickerLivePane(
                pane = SplitPane.PRIMARY,
                hostTaskId = PRIMARY_PICKER_TASK,
                appTaskId = PRIMARY_APP_TASK,
                appPackageName = NAVIGATOR,
            ),
            SplitPane.SECONDARY to SplitPickerLivePane(
                pane = SplitPane.SECONDARY,
                hostTaskId = SECONDARY_PICKER_TASK,
                appTaskId = SECONDARY_APP_TASK,
                appPackageName = MUSIC,
            ),
        )
        fake.area = 0

        // Home выселил детей широкой панели; база умерла, приложение отвязано живым.
        fake.detachTask(SECONDARY_APP_TASK)
        fake.removeActivity(SECONDARY_ROOT, SECONDARY_PICKER_ACTIVITY)
        assertFalse(split.allRecordedMembersAlive(scene, PICKER_COMPONENTS))
        assertTrue("каждое записанное приложение живо", split.allRecordedAppsAlive(scene))

        // А вот смерть записанного приложения якорь обязан увидеть.
        fake.removeActivity(DETACHED_ROOT, "$MUSIC.MainActivity")
        assertFalse(split.allRecordedAppsAlive(scene))
    }

    @Test
    fun dividerHintOverACoveredSceneSkipsTheSettleAndReadsOnly() {
        // Правка W1 (v20 D1): над накрытой сценой - Home (area 0) или чужое fullscreen-окно
        // (area 4) - дивайдера нет, и реконсил обязан ответить одним чтением area: без слепой
        // pause(1500), которая держала единственного воркера ~2 с перед каждым open, и без
        // чтения топологии. Существование накрытой сцены проверяет вызывающий (инвариант 5).
        val fake = FakeShell()
        val settled = mutableListOf<Long>()
        val split = SplitPickerShellSession(
            shell = fake::shell,
            apkPath = "/data/app/dev.denza.apps/base.apk",
            settle = { millis -> settled += millis },
        )
        val previous = mapOf(
            SplitPane.PRIMARY to SplitPickerObservedPane(hostTaskId = 60),
            SplitPane.SECONDARY to SplitPickerObservedPane(
                hostTaskId = 61,
                appTaskId = 71,
                packageName = MUSIC,
            ),
        )

        listOf(0, 4).forEach { covered ->
            fake.area = covered
            fake.commands.clear()
            settled.clear()

            assertEquals(null, split.reconcileDividerResize(PICKER_COMPONENTS, previous))

            assertEquals(
                "над area $covered реконсил стоит ровно одно чтение area",
                listOf("service call activity_task 30"),
                fake.commands.toList(),
            )
            assertEquals("и ни одной паузы", emptyList<Long>(), settled.toList())
        }
    }

    @Test
    fun dividerHintOverALiveAreaStillWaitsOutTheFirmwareSettle() {
        // Правка W1 меняет только накрытые area: над живым split (area 3) дивайдерный settle
        // остаётся неизменной частью live-proven рецепта.
        val fake = FakeShell()
        val settled = mutableListOf<Long>()
        val split = SplitPickerShellSession(
            shell = fake::shell,
            apkPath = "/data/app/dev.denza.apps/base.apk",
            settle = { millis -> settled += millis },
        )
        fake.area = 3

        split.reconcileDividerResize(
            PICKER_COMPONENTS,
            mapOf(
                SplitPane.PRIMARY to SplitPickerObservedPane(hostTaskId = 60),
                SplitPane.SECONDARY to SplitPickerObservedPane(
                    hostTaskId = 61,
                    appTaskId = 71,
                    packageName = MUSIC,
                ),
            ),
        )

        assertTrue("settle 1.5 c по-прежнему отрабатывает", 1_500L in settled)
    }

    @Test
    fun ownedSceneCoveredByFullscreenIsFocusedInsteadOfRebuilt() {
        val fake = FakeShell()
        val split = session(fake)
        val hosts = split.buildPickers()
        val navigator = split.selectApp(
            pickerTaskId = hosts.getValue(SplitPane.PRIMARY),
            target = SplitLaunchTarget(NAVIGATOR, "$NAVIGATOR/$NAVIGATOR.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        val music = split.selectApp(
            pickerTaskId = hosts.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        fake.area = 4
        fake.commands.clear()

        val hidden = checkNotNull(
            split.existingOwnedSession(
                PICKER_COMPONENTS,
                mapOf(
                    SplitPane.PRIMARY to SplitPickerExpectedApp(
                        navigator.appTaskId,
                        navigator.packageName,
                    ),
                    SplitPane.SECONDARY to SplitPickerExpectedApp(
                        music.appTaskId,
                        music.packageName,
                    ),
                ),
            ),
        )
        val revealed = split.revealOwnedSession(hidden, PICKER_COMPONENTS)

        assertEquals(hidden, revealed)
        assertEquals(3, fake.area)
        assertTrue(fake.commands.any { it == "am task focus ${hidden.getValue(SplitPane.PRIMARY).appTaskId}" })
        assertFalse(
            fake.commands.any { command ->
                command.startsWith("am start ") ||
                    command.startsWith("am stack move-task ") ||
                    command.contains(" remove-task ")
            },
        )
    }

    /**
     * 1.9.4 and the whole of правка E: Home covers a scene, it does not end one (invariant 5).
     *
     * Acceptance v17 refused every single adoption because the area under Home is 0, rebuilt the
     * pair from scratch and restarted the music every time the user came back.
     */
    @Test
    fun ownedSceneCoveredByHomeIsRaisedByExactIdentityAlone() {
        val fake = FakeShell()
        val split = session(fake)
        val hosts = split.buildPickers()
        val navigator = split.selectApp(
            pickerTaskId = hosts.getValue(SplitPane.PRIMARY),
            target = launchTargetOf(NAVIGATOR),
            pickerComponents = PICKER_COMPONENTS,
        )
        val music = split.selectApp(
            pickerTaskId = hosts.getValue(SplitPane.SECONDARY),
            target = launchTargetOf(MUSIC),
            pickerComponents = PICKER_COMPONENTS,
        )
        fake.area = 0
        fake.commands.clear()

        // Без точных идентичностей - а их не переживает смерть процесса - адопции нет вовсе.
        val guessed = split.readOwnedSession(PICKER_COMPONENTS)
        assertEquals(null, guessed.scene)
        assertTrue("и марка называет причину", guessed.reason.contains("PRIMARY"))

        val hidden = checkNotNull(
            split.existingOwnedSession(
                PICKER_COMPONENTS,
                mapOf(
                    SplitPane.PRIMARY to SplitPickerExpectedApp(
                        navigator.appTaskId,
                        navigator.packageName,
                    ),
                    SplitPane.SECONDARY to SplitPickerExpectedApp(music.appTaskId, music.packageName),
                ),
            ),
        )
        val revealed = split.revealOwnedSession(hidden, PICKER_COMPONENTS)

        assertEquals(hidden, revealed)
        assertEquals(3, fake.area)
        assertTrue(fake.commands.any { it == "am task focus ${navigator.appTaskId}" })
        assertFalse(
            "поднятие сцены не двигает и не создаёт ни одной задачи",
            fake.commands.any { command ->
                command.startsWith("am start ") ||
                    command.startsWith("am stack move-task ") ||
                    command.contains(" remove-task ")
            },
        )
    }

    /** A pane holding nothing but its own picker names no app, and the root itself proves it. */
    @Test
    fun aCoveredPaneWithOnlyItsPickerNeedsNoIdentityToBeAdopted() {
        val fake = FakeShell()
        val split = session(fake)
        val hosts = split.buildPickers()
        val music = split.selectApp(
            pickerTaskId = hosts.getValue(SplitPane.SECONDARY),
            target = launchTargetOf(MUSIC),
            pickerComponents = PICKER_COMPONENTS,
        )
        fake.area = 0

        val hidden = split.existingOwnedSession(
            PICKER_COMPONENTS,
            mapOf(SplitPane.SECONDARY to SplitPickerExpectedApp(music.appTaskId, music.packageName)),
        )

        assertEquals(hosts.getValue(SplitPane.PRIMARY), hidden?.get(SplitPane.PRIMARY)?.hostTaskId)
        assertEquals(null, hidden?.get(SplitPane.PRIMARY)?.appTaskId)
        assertEquals(music.appTaskId, hidden?.get(SplitPane.SECONDARY)?.appTaskId)

        // И обратное: панель, в которой живёт приложение, под накрытой сценой не усыновляется без
        // точной идентичности - даже когда прошивка отчитывается нашим пикером как верхним. Иначе
        // сцена была бы принята как «пикер|пикер», а живое приложение забыто (правка E1).
        fake.promoteActivity(SECONDARY_ROOT, SECONDARY_PICKER_ACTIVITY)

        assertEquals(null, split.existingOwnedSession(PICKER_COMPONENTS))
    }

    /**
     * Правка волны 14 (приёмка v29, дефект D): панель, где у одного приложения несколько живых
     * задач, остаётся НАШЕЙ панелью.
     *
     * Живьём (v28, дефект 0) тап по Яндекс.Музыке привёл в панельный корень И t316, И t532, обе
     * видимые, - и экран при этом был правильным: сверху стояло выбранное приложение. Панель -
     * это её база и ОДНО приложение, а сколькими задачами прошивка это одно приложение
     * представляет, решает прошивка (1.5.2). Счёт задач вместо счёта приложений объявлял такую
     * панель чужой, то есть НЕ УСЫНОВЛЯЕМОЙ, - а это значит, что следующее открытие пересобрало
     * бы живую сцену и перезапустило играющее приложение (U2, 1.3.5).
     */
    @Test
    fun aPaneWhoseAppOwnsSeveralLiveTasksIsStillOurOwnPane() {
        val fake = FakeShell()
        val split = session(fake)
        val hosts = split.buildPickers()
        fake.addTask(PRIMARY_ROOT, 316, MUSIC, "$MUSIC.MainActivity")
        fake.addTask(PRIMARY_ROOT, 532, MUSIC, "$MUSIC.MainActivity")

        val read = split.readOwnedSession(PICKER_COMPONENTS)

        assertEquals("adoptable", read.reason)
        assertEquals(
            hosts.getValue(SplitPane.PRIMARY),
            read.scene?.get(SplitPane.PRIMARY)?.hostTaskId,
        )
        assertEquals(
            "окно панели - фактически верхняя задача приложения",
            532,
            read.scene?.get(SplitPane.PRIMARY)?.appTaskId,
        )
        assertEquals(MUSIC, read.scene?.get(SplitPane.PRIMARY)?.appPackageName)
    }

    /**
     * И ровно та граница, ради которой счёт вообще существует: несколько задач одного приложения -
     * это одно приложение, а вторая ЧУЖАЯ задача под ним - уже не наша панель (инвариант 3).
     */
    @Test
    fun aPaneHidingAForeignTaskUnderItsAppIsStillRefused() {
        val fake = FakeShell()
        val split = session(fake)
        split.buildPickers()
        fake.addTask(PRIMARY_ROOT, 316, NAVIGATOR, "$NAVIGATOR.MainActivity")
        fake.addTask(PRIMARY_ROOT, 532, MUSIC, "$MUSIC.MainActivity")

        val read = split.readOwnedSession(PICKER_COMPONENTS)

        assertEquals(null, read.scene)
        assertEquals("PRIMARY: пикеров 1, задач 3", read.reason)
    }

    @Test
    fun sceneMissingOneOwnedBaseIsNotAdoptedAndSaysWhich() {
        val fake = FakeShell().apply {
            area = 3
            addTask(PRIMARY_ROOT, 40, NAVIGATOR, "$NAVIGATOR.MainActivity")
        }

        val read = session(fake).readOwnedSession(PICKER_COMPONENTS)

        assertEquals(null, read.scene)
        assertEquals(
            "марка называет панель и то, что там нашли, а не «ничего нашего»",
            "PRIMARY: пикеров 0, задач 1",
            read.reason,
        )
    }

    @Test
    fun nativeEdgeCollapseAdoptsOnlyTheSurvivingOwnedRoot() {
        val fake = FakeShell()
        val split = session(fake)
        val hosts = split.buildPickers()
        val navigator = split.selectApp(
            pickerTaskId = hosts.getValue(SplitPane.PRIMARY),
            target = SplitLaunchTarget(NAVIGATOR, "$NAVIGATOR/$NAVIGATOR.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        fake.dismissPane(PRIMARY_ROOT)
        fake.commands.clear()

        val collapsed = split.collapsedOwnedSession(
            pickerComponents = PICKER_COMPONENTS,
            expectedPanes = mapOf(
                SplitPane.PRIMARY to SplitPickerObservedPane(
                    hostTaskId = hosts.getValue(SplitPane.PRIMARY),
                    appTaskId = navigator.appTaskId,
                    packageName = navigator.packageName,
                ),
                SplitPane.SECONDARY to SplitPickerObservedPane(
                    hostTaskId = hosts.getValue(SplitPane.SECONDARY),
                ),
            ),
        )

        assertEquals(SplitPane.SECONDARY, collapsed?.pane)
        assertEquals(hosts.getValue(SplitPane.SECONDARY), collapsed?.hostTaskId)
        assertEquals(null, collapsed?.appTaskId)
        assertFalse(
            fake.commands.any { command ->
                command.startsWith("am start ") ||
                    command.startsWith("am stack move-task ") ||
                    command.startsWith("am task focus ") ||
                    command.contains(" remove-task ")
            },
        )
    }

    @Test
    fun nativeEdgeCollapseAcceptsRecordedTaskDetachedOutsideNativeRoots() {
        val fake = FakeShell()
        val split = session(fake)
        val hosts = split.buildPickers()
        val vanishedHost = hosts.getValue(SplitPane.PRIMARY)
        fake.dismissPane(PRIMARY_ROOT)
        fake.addTask(FULL_ROOT, vanishedHost, SPLIT_HOST_PACKAGE, PRIMARY_PICKER_ACTIVITY)

        val collapsed = split.collapsedOwnedSession(
            pickerComponents = PICKER_COMPONENTS,
            expectedPanes = mapOf(
                SplitPane.PRIMARY to SplitPickerObservedPane(vanishedHost),
                SplitPane.SECONDARY to SplitPickerObservedPane(
                    hosts.getValue(SplitPane.SECONDARY),
                ),
            ),
        )

        assertEquals(SplitPane.SECONDARY, collapsed?.pane)
        assertEquals(hosts.getValue(SplitPane.SECONDARY), collapsed?.hostTaskId)
    }

    @Test
    fun nativeEdgeCollapseRejectsTrackedTaskStillInCollapsedNativeRoot() {
        val fake = FakeShell()
        val split = session(fake)
        val hosts = split.buildPickers()
        val vanishedHost = hosts.getValue(SplitPane.PRIMARY)
        fake.dismissPane(PRIMARY_ROOT)
        fake.addTask(PRIMARY_ROOT, vanishedHost, SPLIT_HOST_PACKAGE, PRIMARY_PICKER_ACTIVITY)

        val collapsed = split.collapsedOwnedSession(
            pickerComponents = PICKER_COMPONENTS,
            expectedPanes = mapOf(
                SplitPane.PRIMARY to SplitPickerObservedPane(vanishedHost),
                SplitPane.SECONDARY to SplitPickerObservedPane(
                    hosts.getValue(SplitPane.SECONDARY),
                ),
            ),
        )

        assertEquals(null, collapsed)
    }

    @Test
    fun nativeEdgeCollapseReattachesExactPickerBehindAppMovedToSurvivorRoot() {
        val fake = FakeShell()
        val split = session(fake)
        val hosts = split.buildPickers()
        val music = split.selectApp(
            pickerTaskId = hosts.getValue(SplitPane.PRIMARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        val navigator = split.selectApp(
            pickerTaskId = hosts.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(NAVIGATOR, "$NAVIGATOR/$NAVIGATOR.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )

        // Live DiLink 5.1 trace: area 2/root 3 survived, but BYD moved the app previously
        // recorded in PRIMARY there and detached both permanent picker bases.
        fake.moveTask(hosts.getValue(SplitPane.PRIMARY), FULL_ROOT)
        fake.moveTask(hosts.getValue(SplitPane.SECONDARY), FULL_ROOT)
        fake.moveTask(navigator.appTaskId, FULL_ROOT)
        fake.moveTask(music.appTaskId, FULL_ROOT)
        fake.area = 2
        fake.moveTask(music.appTaskId, SECONDARY_ROOT)
        fake.commands.clear()

        val collapsed = split.collapsedOwnedSession(
            pickerComponents = PICKER_COMPONENTS,
            expectedPanes = mapOf(
                SplitPane.PRIMARY to SplitPickerObservedPane(
                    hostTaskId = hosts.getValue(SplitPane.PRIMARY),
                    appTaskId = music.appTaskId,
                    packageName = music.packageName,
                ),
                SplitPane.SECONDARY to SplitPickerObservedPane(
                    hostTaskId = hosts.getValue(SplitPane.SECONDARY),
                    appTaskId = navigator.appTaskId,
                    packageName = navigator.packageName,
                ),
            ),
        )

        assertEquals(SplitPane.SECONDARY, collapsed?.pane)
        assertEquals(hosts.getValue(SplitPane.PRIMARY), collapsed?.hostTaskId)
        assertEquals(music.appTaskId, collapsed?.appTaskId)
        assertEquals(MUSIC, collapsed?.appPackageName)
        assertEquals(SECONDARY_ROOT, fake.taskRoot(hosts.getValue(SplitPane.PRIMARY)))
        assertEquals(music.appTaskId.toString(), fake.topTaskId(SECONDARY_ROOT)?.toString())
        assertTrue(
            fake.commands.any {
                it == "am stack move-task ${hosts.getValue(SplitPane.PRIMARY)} " +
                    "$SECONDARY_ROOT false"
            },
        )
        assertFalse(
            fake.commands.any { command ->
                command.startsWith("am start ") ||
                    command.startsWith("am task focus ") ||
                    command.contains(" remove-task ")
            },
        )
    }

    @Test
    fun fullscreenAppDismissalAdoptsItsExactPickerAsTheSurvivor() {
        val fake = FakeShell()
        val split = session(fake)
        val hosts = split.buildPickers()
        val music = split.selectApp(
            pickerTaskId = hosts.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        fake.dismissPane(PRIMARY_ROOT)
        fake.moveTask(music.appTaskId, FULL_ROOT)
        fake.commands.clear()

        val collapsed = split.collapsedOwnedSession(
            pickerComponents = PICKER_COMPONENTS,
            expectedPanes = mapOf(
                SplitPane.SECONDARY to SplitPickerObservedPane(
                    hostTaskId = hosts.getValue(SplitPane.SECONDARY),
                    appTaskId = music.appTaskId,
                    packageName = music.packageName,
                ),
            ),
        )

        assertEquals(SplitPane.SECONDARY, collapsed?.pane)
        assertEquals(hosts.getValue(SplitPane.SECONDARY), collapsed?.hostTaskId)
        assertEquals(null, collapsed?.appTaskId)
        assertFalse(
            fake.commands.any { command ->
                command.startsWith("am start ") ||
                    command.startsWith("am stack move-task ") ||
                    command.contains(" remove-task ")
            },
        )
    }

    /**
     * Правка W1 (1.8.2, диагноз v21 Д1): факт схлопывания доказывается существованием - при
     * area 1/2 схлопнута панель, чьи записанные host И app (по exact identity) отсутствуют в
     * обоих панельных root. Полный постусловный набор физической адопции остаётся воротами
     * reattach, не воротами этого факта.
     */
    @Test
    fun collapseByExistenceNamesThePaneWhoseRecordedTasksLeftBothPanelRoots() {
        val fake = FakeShell()
        val split = session(fake)
        val hosts = split.buildPickers()
        val music = split.selectApp(
            pickerTaskId = hosts.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        // Двухпроходный teardown прошивки: host и app схлопнутой панели отвязаны живыми.
        fake.detachTask(hosts.getValue(SplitPane.SECONDARY))
        fake.detachTask(music.appTaskId)
        fake.area = 1
        fake.commands.clear()

        val expected = mapOf(
            SplitPane.PRIMARY to SplitPickerObservedPane(
                hostTaskId = hosts.getValue(SplitPane.PRIMARY),
            ),
            SplitPane.SECONDARY to SplitPickerObservedPane(
                hostTaskId = hosts.getValue(SplitPane.SECONDARY),
                appTaskId = music.appTaskId,
                packageName = music.packageName,
            ),
        )

        assertEquals(
            SplitPane.SECONDARY,
            split.collapsedPaneByExistence(PICKER_COMPONENTS, expected),
        )
        assertFalse(
            "доказательство по существованию строго read-only",
            fake.commands.any { command ->
                command.startsWith("am start ") ||
                    command.startsWith("am stack move-task ") ||
                    command.startsWith("am task ") ||
                    command.contains(" remove-task ")
            },
        )
    }

    @Test
    fun collapseByExistenceRefusesACrashSignatureAndAWholeSceneEnd() {
        val fake = FakeShell()
        val split = session(fake)
        val hosts = split.buildPickers()
        val expected = mapOf(
            SplitPane.PRIMARY to SplitPickerObservedPane(hosts.getValue(SplitPane.PRIMARY)),
            SplitPane.SECONDARY to SplitPickerObservedPane(hosts.getValue(SplitPane.SECONDARY)),
        )

        // Сигнатура краха 1.7.3: host жив в панельном root - его панель не схлопнута.
        fake.area = 1
        assertEquals(null, split.collapsedPaneByExistence(PICKER_COMPONENTS, expected))

        // Обе панели покинули root'ы под НАКРЫТИЕМ - это конец сцены, и решает его
        // existence-проверка конца, а не collapse (правка W1 волны 9 сюда не дотягивается:
        // area 0/4 отказывает раньше всех прочих чтений).
        fake.detachTask(hosts.getValue(SplitPane.PRIMARY))
        fake.detachTask(hosts.getValue(SplitPane.SECONDARY))
        fake.area = 0
        assertEquals(null, split.collapsedPaneByExistence(PICKER_COMPONENTS, expected))
        fake.area = 4
        assertEquals(null, split.collapsedPaneByExistence(PICKER_COMPONENTS, expected))

        // Вне area 1/2 факт схлопывания не читается вовсе.
        fake.area = 3
        assertEquals(null, split.collapsedPaneByExistence(PICKER_COMPONENTS, expected))
    }

    /**
     * Правка W1 волны 9 (приёмка v24, Д1): когда панельные корни покинули ОБЕ записанные панели,
     * выжившую называет area, а схлопнута - другая. Живая геометрия дефекта: схлопнулась широкая
     * SECOND справа, выжившая узкая ушла fullscreen вместе со смертью своей пикер-базы, и её
     * приложение стоит уже в полноэкранном корне. До волны 9 эта ветка отказывала безусловно,
     * слот схлопнутой панели не чистился, и следующий open воскрешал её приложение.
     */
    @Test
    fun collapseByExistenceNamesTheCollapsedPaneWhenAreaNamesTheSurvivor() {
        val fake = FakeShell()
        val split = session(fake)
        val hosts = split.buildPickers()
        val navigator = split.selectApp(
            pickerTaskId = hosts.getValue(SplitPane.PRIMARY),
            target = SplitLaunchTarget(NAVIGATOR, "$NAVIGATOR/$NAVIGATOR.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        val music = split.selectApp(
            pickerTaskId = hosts.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        val expected = mapOf(
            SplitPane.PRIMARY to SplitPickerObservedPane(
                hostTaskId = hosts.getValue(SplitPane.PRIMARY),
                appTaskId = navigator.appTaskId,
                packageName = navigator.packageName,
            ),
            SplitPane.SECONDARY to SplitPickerObservedPane(
                hostTaskId = hosts.getValue(SplitPane.SECONDARY),
                appTaskId = music.appTaskId,
                packageName = music.packageName,
            ),
        )

        // Широкая SECOND схлопнута: прошивка отвязала её host и app живыми. Выжившая узкая ушла
        // fullscreen: её база мертва, её приложение - уже в полноэкранном корне.
        fake.detachTask(hosts.getValue(SplitPane.SECONDARY))
        fake.detachTask(music.appTaskId)
        fake.removeActivity(PRIMARY_ROOT, PRIMARY_PICKER_ACTIVITY)
        fake.moveTask(navigator.appTaskId, FULL_ROOT)
        fake.area = 1
        fake.commands.clear()

        val read = split.readCollapsedPaneByExistence(PICKER_COMPONENTS, expected)

        assertEquals(SplitPane.SECONDARY, read.collapsed)
        assertEquals("collapsed: выживший назван area=1", read.reason)
        assertFalse(
            "доказательство по существованию строго read-only",
            fake.commands.any { command ->
                command.startsWith("am start ") ||
                    command.startsWith("am stack move-task ") ||
                    command.startsWith("am task ") ||
                    command.contains(" remove-task ")
            },
        )

        // Зеркальная area называет зеркального выжившего.
        fake.area = 2
        assertEquals(
            SplitPane.PRIMARY,
            split.collapsedPaneByExistence(PICKER_COMPONENTS, expected),
        )
    }

    /**
     * Правка W1 волны 9, fail-closed: имя схлопнутой панели даёт area, existence его лишь
     * подтверждает. Панель, покинувшая панельные корни, но названная area ВЫЖИВШЕЙ, - расхождение
     * двух чтений одного мира (переходный такт; задержавшаяся в корне задача схлопнутой панели), и
     * ответ на него - отказ: закрыть по нему слот значило бы закрыть выжившую панель и забыть
     * выбор пользователя.
     */
    @Test
    fun collapseByExistenceRefusesWhenTheAbsentPaneIsTheOneAreaCallsTheSurvivor() {
        val fake = FakeShell()
        val split = session(fake)
        val hosts = split.buildPickers()
        val navigator = split.selectApp(
            pickerTaskId = hosts.getValue(SplitPane.PRIMARY),
            target = SplitLaunchTarget(NAVIGATOR, "$NAVIGATOR/$NAVIGATOR.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        val music = split.selectApp(
            pickerTaskId = hosts.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        val expected = mapOf(
            SplitPane.PRIMARY to SplitPickerObservedPane(
                hostTaskId = hosts.getValue(SplitPane.PRIMARY),
                appTaskId = navigator.appTaskId,
                packageName = navigator.packageName,
            ),
            SplitPane.SECONDARY to SplitPickerObservedPane(
                hostTaskId = hosts.getValue(SplitPane.SECONDARY),
                appTaskId = music.appTaskId,
                packageName = music.packageName,
            ),
        )

        // Корни покинула ровно одна панель - PRIMARY, - а area называет выжившей её же.
        fake.detachTask(hosts.getValue(SplitPane.PRIMARY))
        fake.detachTask(navigator.appTaskId)
        fake.area = 1

        val read = split.readCollapsedPaneByExistence(PICKER_COMPONENTS, expected)

        assertEquals(null, read.collapsed)
        assertEquals("панель, покинувшая корни, названа выжившей area=1", read.reason)
    }

    /**
     * Правка волны 12: схлопывание доказано после того, как Home уже накрыл экран.
     *
     * Живой протокол 2026-08-25 (D2/D4 на машине): пользователь схлопнул панель, нажал Home, и
     * `area` ушла в 0 через ~0.2 с - оба прежних доказательства с этого мгновения слепы навсегда.
     * Панельные контейнеры при этом читаются `[1704,112][2536,1472]` и `[0,0][2560,1600]`: один
     * растянут схлопыванием на весь экран, и накрытие этого не отменяет. Имя выжившей панели даёт
     * не area, а её ОКНО, живое ровно в границах растянутого корня.
     *
     * Правка волны 13: окно панели с приложением - это приложение, и база при этом остаётся на
     * панельных границах (приёмка v28 A1: базы `[24,112][856,1472]` и `[880,112][2536,1472]`,
     * выживший app t520 `[0,0][2560,1600]`). Именно эта форма мира ломала волну 12: она искала
     * на растянутых границах базу и отказывала 4 раза из 4.
     */
    @Test
    fun collapseByPanelBoundsNamesTheClosedPaneAfterHomeCoveredTheScreen() {
        val fake = FakeShell()
        val split = session(fake)
        val hosts = split.buildPickers()
        val navigator = split.selectApp(
            pickerTaskId = hosts.getValue(SplitPane.PRIMARY),
            target = SplitLaunchTarget(NAVIGATOR, "$NAVIGATOR/$NAVIGATOR.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        val music = split.selectApp(
            pickerTaskId = hosts.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        val expected = mapOf(
            SplitPane.PRIMARY to SplitPickerObservedPane(
                hostTaskId = hosts.getValue(SplitPane.PRIMARY),
                appTaskId = navigator.appTaskId,
                packageName = navigator.packageName,
            ),
            SplitPane.SECONDARY to SplitPickerObservedPane(
                hostTaskId = hosts.getValue(SplitPane.SECONDARY),
                appTaskId = music.appTaskId,
                packageName = music.packageName,
            ),
        )

        // Жест: панель музыки закрыта, её задачи отвязаны живыми, контейнер выжившей растянут -
        // и растянуто в нём ПРИЛОЖЕНИЕ выжившего, а его база остаётся на панельных границах.
        fake.detachTask(hosts.getValue(SplitPane.SECONDARY))
        fake.detachTask(music.appTaskId)
        fake.stretchPanelRoot(PRIMARY_ROOT, baseKeepsPanelBounds = true)
        // ...и Home накрыл экран прежде, чем кто-либо успел это прочесть.
        fake.area = 0
        fake.commands.clear()

        val read = split.collapsedPaneByPanelBounds(PICKER_COMPONENTS, expected)

        assertEquals(SplitPane.SECONDARY, read.collapsed)
        assertEquals("collapsed: приложение выжившего растянуто на весь экран", read.reason)
        assertEquals(
            "база выжившего осталась панельной - именно на этом сломалась волна 12",
            PRIMARY_BOUNDS,
            fake.taskBounds(hosts.getValue(SplitPane.PRIMARY)),
        )
        assertFalse(
            "доказательство строго read-only",
            fake.commands.any { command ->
                command.startsWith("am start ") ||
                    command.startsWith("am stack move-task ") ||
                    command.startsWith("am task ") ||
                    command.contains(" remove-task ")
            },
        )

        // Зеркальная сторона дивайдера: растянут другой контейнер, закрыта другая панель. Сцена
        // «пикер|пикер» - выживший-пикер, у которого приложения нет вовсе (1.8.2, v25-B(iii)):
        // окном служит сама база, и прошивка растягивает её.
        val mirror = FakeShell()
        val mirrorSplit = session(mirror)
        val mirrorHosts = mirrorSplit.buildPickers()
        mirror.detachTask(mirrorHosts.getValue(SplitPane.PRIMARY))
        mirror.stretchPanelRoot(SECONDARY_ROOT, baseKeepsPanelBounds = true)
        mirror.area = 0
        val mirrorRead = mirrorSplit.collapsedPaneByPanelBounds(
            PICKER_COMPONENTS,
            mapOf(
                SplitPane.PRIMARY to
                    SplitPickerObservedPane(mirrorHosts.getValue(SplitPane.PRIMARY)),
                SplitPane.SECONDARY to
                    SplitPickerObservedPane(mirrorHosts.getValue(SplitPane.SECONDARY)),
            ),
        )
        assertEquals(SplitPane.PRIMARY, mirrorRead.collapsed)
        assertEquals("collapsed: пикер выжившего растянут на весь экран", mirrorRead.reason)
    }

    /**
     * Правка волны 13 (П1): свежее схлопывание до накрытия - вторая измеренная форма того же
     * мира, и признак обязан работать в обеих.
     *
     * `21-after-dragL.stack.txt` (evidence/dock-split-v19): растянутый корень
     * `RootTask id=3 bounds=[0,0][2560,1600]` держит И базу t94, И музыку t97, обе на
     * полноэкранных границах, а задачи закрытой панели уже покинули оба панельных корня.
     */
    @Test
    fun collapseByPanelBoundsNamesTheClosedPaneWhileTheWholeSurvivingRootIsStretched() {
        val fake = FakeShell()
        val split = session(fake)
        val hosts = split.buildPickers()
        val music = split.selectApp(
            pickerTaskId = hosts.getValue(SplitPane.PRIMARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        val expected = mapOf(
            SplitPane.PRIMARY to SplitPickerObservedPane(
                hostTaskId = hosts.getValue(SplitPane.PRIMARY),
                appTaskId = music.appTaskId,
                packageName = music.packageName,
            ),
            SplitPane.SECONDARY to SplitPickerObservedPane(hosts.getValue(SplitPane.SECONDARY)),
        )

        fake.detachTask(hosts.getValue(SplitPane.SECONDARY))
        fake.stretchPanelRoot(PRIMARY_ROOT)
        fake.area = 1

        val read = split.collapsedPaneByPanelBounds(PICKER_COMPONENTS, expected)

        assertEquals(SplitPane.SECONDARY, read.collapsed)
        assertEquals("collapsed: приложение выжившего растянуто на весь экран", read.reason)
    }

    /**
     * Обратная сторона той же правки, и она важнее прямой: ложное закрытие теряет выбор
     * пользователя, пропущенное - нет.
     *
     * Обычный Home над живой парой измерен на машине в тот же день: контейнеры остаются
     * `[24,112][1680,1472]` и `[1704,112][2536,1472]` - ни один не вложен в другой, и предикат
     * не имеет права сказать ни слова. Это проверка B приёмки v27/v28 (шесть обычных Home подряд,
     * ни одного ложного закрытия), выраженная предикатом.
     *
     * Правка волны 13: обе живые геометрии дивайдера - оба детента, - потому что признак
     * выжившего теперь читает границы задач, а не только корней.
     */
    @Test
    fun collapseByPanelBoundsSaysNothingAboutAPlainHomeOverALivePair() {
        listOf(
            FakeShell(),
            FakeShell(
                primaryPaneBounds = MIRRORED_PRIMARY_BOUNDS,
                secondaryPaneBounds = MIRRORED_SECONDARY_BOUNDS,
            ),
        ).forEach { fake ->
            val split = session(fake)
            val hosts = split.buildPickers()
            val navigator = split.selectApp(
                pickerTaskId = hosts.getValue(SplitPane.PRIMARY),
                target = SplitLaunchTarget(NAVIGATOR, "$NAVIGATOR/$NAVIGATOR.MainActivity"),
                pickerComponents = PICKER_COMPONENTS,
            )
            val music = split.selectApp(
                pickerTaskId = hosts.getValue(SplitPane.SECONDARY),
                target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
                pickerComponents = PICKER_COMPONENTS,
            )
            val expected = mapOf(
                SplitPane.PRIMARY to SplitPickerObservedPane(
                    hostTaskId = hosts.getValue(SplitPane.PRIMARY),
                    appTaskId = navigator.appTaskId,
                    packageName = navigator.packageName,
                ),
                SplitPane.SECONDARY to SplitPickerObservedPane(
                    hostTaskId = hosts.getValue(SplitPane.SECONDARY),
                    appTaskId = music.appTaskId,
                    packageName = music.packageName,
                ),
            )

            fake.area = 0
            val read = split.collapsedPaneByPanelBounds(PICKER_COMPONENTS, expected)

            assertEquals(null, read.collapsed)
            assertEquals("панельные корни не вложены", read.reason)

            // И над чужим полноэкранным окном (area=4) ответ тот же: пара цела.
            fake.area = 4
            assertEquals(
                null,
                split.collapsedPaneByPanelBounds(PICKER_COMPONENTS, expected).collapsed,
            )
        }
    }

    /**
     * Растянутый корень - только первое слово. Пока записанные задачи «закрытой» панели ещё
     * стоят в панельных корнях, схлопывание не доказано: это переходный такт двухпроходного
     * teardown, а не решение пользователя.
     */
    @Test
    fun collapseByPanelBoundsRefusesWhileTheClosedPaneStillHoldsAPanelRoot() {
        val fake = FakeShell()
        val split = session(fake)
        val hosts = split.buildPickers()
        val expected = mapOf(
            SplitPane.PRIMARY to SplitPickerObservedPane(hosts.getValue(SplitPane.PRIMARY)),
            SplitPane.SECONDARY to SplitPickerObservedPane(hosts.getValue(SplitPane.SECONDARY)),
        )

        fake.stretchPanelRoot(PRIMARY_ROOT)
        fake.area = 0

        assertEquals(
            "задачи закрытой панели ещё в панельных корнях",
            split.collapsedPaneByPanelBounds(PICKER_COMPONENTS, expected).reason,
        )

        // Как только база закрытой панели ушла из корней, имя названо.
        fake.detachTask(hosts.getValue(SplitPane.SECONDARY))
        assertEquals(
            SplitPane.SECONDARY,
            split.collapsedPaneByPanelBounds(PICKER_COMPONENTS, expected).collapsed,
        )
    }

    /**
     * Процесс, ничего не помнящий, ничего и не утверждает (инвариант 4): без записанной
     * двухпанельной сцены геометрия корней не значит ничего.
     */
    @Test
    fun collapseByPanelBoundsClaimsNothingWithoutATwoPaneRecord() {
        val fake = FakeShell()
        val split = session(fake)
        val hosts = split.buildPickers()
        fake.detachTask(hosts.getValue(SplitPane.SECONDARY))
        fake.stretchPanelRoot(PRIMARY_ROOT)
        fake.area = 0

        assertEquals(
            "сцена не записана двухпанельной",
            split.collapsedPaneByPanelBounds(PICKER_COMPONENTS, emptyMap()).reason,
        )
        assertEquals(
            "сцена не записана двухпанельной",
            split.collapsedPaneByPanelBounds(
                PICKER_COMPONENTS,
                mapOf(SplitPane.PRIMARY to SplitPickerObservedPane(hosts.getValue(SplitPane.PRIMARY))),
            ).reason,
        )
    }

    /** Правка W4 (U5): обе collapse-проверки называют отказавший предикат, не молчат `null`-ом. */
    @Test
    fun collapseReadsNameTheirRefusedPredicate() {
        val fake = FakeShell()
        val split = session(fake)
        val hosts = split.buildPickers()
        val expected = mapOf(
            SplitPane.PRIMARY to SplitPickerObservedPane(hosts.getValue(SplitPane.PRIMARY)),
            SplitPane.SECONDARY to SplitPickerObservedPane(hosts.getValue(SplitPane.SECONDARY)),
        )

        assertEquals("area=3", split.readCollapsedSession(PICKER_COMPONENTS, expected).reason)
        assertEquals(
            "area=3",
            split.readCollapsedPaneByExistence(PICKER_COMPONENTS, expected).reason,
        )

        // Живая «схлопнутая» панель: физика спотыкается о задачи её корня, существование - о
        // живой host в панельном корне.
        fake.area = 1
        assertEquals(
            "в схлопнутом корне остались задачи",
            split.readCollapsedSession(PICKER_COMPONENTS, expected).reason,
        )
        assertEquals(
            "ни одна панель не покинула панельные корни целиком",
            split.readCollapsedPaneByExistence(PICKER_COMPONENTS, expected).reason,
        )
    }

    @Test
    fun visibleProductPickerHintResolvesOnlyOneExactNativeTask() {
        val fake = FakeShell()
        val split = session(fake)
        val hosts = split.buildPickers()

        assertEquals(null, split.singleVisiblePickerTaskId(PICKER_COMPONENTS))

        split.selectApp(
            pickerTaskId = hosts.getValue(SplitPane.PRIMARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )

        assertEquals(
            hosts.getValue(SplitPane.SECONDARY),
            split.singleVisiblePickerTaskId(PICKER_COMPONENTS),
        )
    }

    @Test
    fun collapsedPickerReattachRollsBackWhenNativeAreaChangesDuringMutation() {
        val fake = FakeShell()
        val split = session(fake)
        val hosts = split.buildPickers()
        val music = split.selectApp(
            pickerTaskId = hosts.getValue(SplitPane.PRIMARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        fake.moveTask(hosts.getValue(SplitPane.PRIMARY), FULL_ROOT)
        fake.moveTask(hosts.getValue(SplitPane.SECONDARY), FULL_ROOT)
        fake.moveTask(music.appTaskId, FULL_ROOT)
        fake.area = 2
        fake.moveTask(music.appTaskId, SECONDARY_ROOT)
        fake.destabilizeAreaOnNextShellMove()
        fake.commands.clear()

        val result = runCatching {
            split.collapsedOwnedSession(
                pickerComponents = PICKER_COMPONENTS,
                expectedPanes = mapOf(
                    SplitPane.PRIMARY to SplitPickerObservedPane(
                        hostTaskId = hosts.getValue(SplitPane.PRIMARY),
                        appTaskId = music.appTaskId,
                        packageName = music.packageName,
                    ),
                    SplitPane.SECONDARY to SplitPickerObservedPane(
                        hostTaskId = hosts.getValue(SplitPane.SECONDARY),
                    ),
                ),
            )
        }

        assertTrue(result.isFailure)
        assertEquals(FULL_ROOT, fake.taskRoot(hosts.getValue(SplitPane.PRIMARY)))
        assertTrue(
            fake.commands.any {
                it == "am stack move-task ${hosts.getValue(SplitPane.PRIMARY)} " +
                    "$SECONDARY_ROOT false"
            },
        )
        assertTrue(
            fake.commands.any {
                it == "am stack move-task ${hosts.getValue(SplitPane.PRIMARY)} $FULL_ROOT false"
            },
        )
        assertFalse(fake.commands.any { it.contains(" remove-task ") })
    }

    @Test
    fun existingPairWithoutPickerBasesGetsThemThroughExactPaneCategories() {
        val fake = FakeShell(tx115RequiresHome = true).apply {
            area = 3
            addTask(PRIMARY_ROOT, 40, NAVIGATOR, "$NAVIGATOR.MainActivity")
            addTask(SECONDARY_ROOT, 41, MUSIC, "$MUSIC.MainActivity")
        }

        val built = session(fake).buildScene(
            PICKERS,
            mapOf(
                SplitPane.PRIMARY to launchTargetOf(NAVIGATOR),
                SplitPane.SECONDARY to launchTargetOf(MUSIC),
            ),
        )

        assertEquals("оба приложения на месте", emptySet<SplitPane>(), built.failed)
        assertTrue(fake.hasActivity(PRIMARY_ROOT, PRIMARY_PICKER_ACTIVITY))
        assertTrue(fake.hasActivity(SECONDARY_ROOT, SECONDARY_PICKER_ACTIVITY))
        assertEquals("$NAVIGATOR.MainActivity", fake.topActivity(PRIMARY_ROOT))
        assertEquals("$MUSIC.MainActivity", fake.topActivity(SECONDARY_ROOT))
        // U2: the two tasks the panes already held are the two that came back.
        assertEquals(40, built.panes.getValue(SplitPane.PRIMARY).appTaskId)
        assertEquals(41, built.panes.getValue(SplitPane.SECONDARY).appTaskId)
        assertFalse(fake.commands.any { it == "input keyevent KEYCODE_HOME" })
        assertFalse(fake.commands.any { it == "service call activity_task 115" })
    }

    @Test(expected = IllegalStateException::class)
    fun explicitOpenFailsClosedWhenNativePaneLaunchIsRejected() {
        val fake = FakeShell(hostingSucceeds = false)

        session(fake).buildPickers()
    }

    @Test
    fun pickerTapPlacesOrdinaryAppTaskAboveItsOwnPicker() {
        val fake = FakeShell()
        val split = session(fake)
        val pickers = split.buildPickers()

        val placement = split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(
                MUSIC,
                "$MUSIC/$MUSIC.MainActivity",
            ),
            pickerComponents = PICKER_COMPONENTS,
        )

        assertEquals("$MUSIC.MainActivity", fake.topActivity(SECONDARY_ROOT))
        assertEquals(SECONDARY_BOUNDS, fake.taskBounds(placement.appTaskId))
        assertTrue(fake.hasActivity(SECONDARY_ROOT, SECONDARY_PICKER_ACTIVITY))
        assertEquals(PRIMARY_PICKER_ACTIVITY, fake.topActivity(PRIMARY_ROOT))
        val appLaunch = fake.commands.first { command ->
            command.startsWith("am start ") && command.contains("'$MUSIC/$MUSIC.MainActivity'")
        }
        assertTrue("обычный выбор переиспользует задачу пакета", appLaunch.contains("-f 0x10200000"))
        assertTrue(appLaunch.contains("byd.intent.category.START_IVI_SECOND"))
        assertFalse(fake.commands.any { it.contains("SplitTaskProxyMain start-in-task ") })
        assertEquals("$MUSIC.MainActivity", fake.taskBaseActivity(placement.appTaskId))
    }

    @Test
    fun pickerTapAfterPeerDismissalKeepsSelectedAppFullscreen() {
        val fake = FakeShell(renderEmptyNativeRootMarker = true)
        val split = session(fake)
        val pickers = split.buildPickers()
        fake.dismissPane(PRIMARY_ROOT)

        val placement = split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(
                MUSIC,
                "$MUSIC/$MUSIC.MainActivity",
            ),
            pickerComponents = PICKER_COMPONENTS,
        )

        assertEquals(2, fake.area)
        assertEquals(FULL, fake.taskBounds(placement.appTaskId))
        assertEquals("$MUSIC.MainActivity", fake.topActivity(SECONDARY_ROOT))
        assertTrue(fake.hasActivity(SECONDARY_ROOT, SECONDARY_PICKER_ACTIVITY))
        assertFalse(fake.commands.any { it == "input keyevent KEYCODE_HOME" })
    }

    /**
     * Live trace 2026-08-16 17:52:09, from the retired host era: Navigator's singleTask launcher
     * could not remain above host #398. It created #399 in the opposite root and made SmartMulti
     * rebuild both panes. That is why a target is launched straight into its own pane.
     */
    @Test
    fun singleTaskLauncherIsPlacedByDirectLaunchIntoItsPane() {
        val fake = FakeShell()
        val split = session(fake)
        val pickers = split.buildPickers()

        val placement = split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.PRIMARY),
            target = SplitLaunchTarget(
                NAVIGATOR,
                "$NAVIGATOR/$NAVIGATOR.MainActivity",
                launchMode = 2,
            ),
            pickerComponents = PICKER_COMPONENTS,
        )

        assertEquals(NAVIGATOR, placement.packageName)
        assertEquals("$NAVIGATOR.MainActivity", fake.topActivity(PRIMARY_ROOT))
        assertTrue(
            fake.commands.any { command ->
                command.startsWith("am start ") &&
                    command.contains("'$NAVIGATOR/$NAVIGATOR.MainActivity'") &&
                    command.contains("byd.intent.category.START_IVI_PRIMARY") &&
                    command.contains("-f 0x10200000")
            },
        )
    }

    @Test
    fun redirectLauncherRecordsRealOrdinaryTask() {
        val fake = FakeShell(redirectOnStartPackage = WAZE)
        val split = session(fake)
        val pickers = split.buildPickers()

        val placement = split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(WAZE, "$WAZE/$WAZE.FreeMapAppActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )

        assertEquals(WAZE, placement.packageName)
        assertEquals("$WAZE.MainActivity", fake.taskBaseActivity(placement.appTaskId))
        assertTrue(fake.hasPackage(SECONDARY_ROOT, WAZE))
        assertFalse(fake.hasActivity(PRIMARY_ROOT, SPLIT_APP_HOST_ACTIVITY))
        assertFalse(fake.hasActivity(SECONDARY_ROOT, SPLIT_APP_HOST_ACTIVITY))
        assertEquals(2, fake.taskCount(SECONDARY_ROOT))
        assertTrue(fake.commands.any { command ->
            command.startsWith("am start ") &&
                command.contains("'$WAZE/$WAZE.FreeMapAppActivity'") &&
                command.contains("-f 0x10200000")
        })
    }

    @Test
    fun redirectedOrdinaryTaskStaysInSelectedPane() {
        val fake = FakeShell(redirectOnStartPackage = MUSIC)
        val split = session(fake)
        val pickers = split.buildPickers()

        val placement = split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.SplashActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )

        assertEquals(MUSIC, placement.packageName)
        assertEquals("$MUSIC.MainActivity", fake.topActivity(SECONDARY_ROOT))
        assertFalse(fake.hasActivity(PRIMARY_ROOT, SPLIT_APP_HOST_ACTIVITY))
        assertFalse(fake.hasActivity(SECONDARY_ROOT, SPLIT_APP_HOST_ACTIVITY))
        assertEquals(2, fake.taskCount(SECONDARY_ROOT))
    }

    @Test
    fun failedDirectLaunchFailsClosedWithInteractivePicker() {
        val fake = FakeShell(
            directTargetLaunchSucceeds = false,
        )
        val split = session(fake)
        val pickers = split.buildPickers()

        runCatching {
            split.selectApp(
                pickerTaskId = pickers.getValue(SplitPane.PRIMARY),
                target = SplitLaunchTarget(NAVIGATOR, "$NAVIGATOR/$NAVIGATOR.MainActivity"),
                pickerComponents = PICKER_COMPONENTS,
            )
        }.onSuccess { error("Expected direct launch to fail") }

        assertEquals(PRIMARY_PICKER_ACTIVITY, fake.topActivity(PRIMARY_ROOT))
        assertFalse(fake.hasActivity(PRIMARY_ROOT, SPLIT_APP_HOST_ACTIVITY))
        assertFalse(fake.hasPackage(PRIMARY_ROOT, NAVIGATOR))
        assertEquals(1, fake.taskCount(PRIMARY_ROOT))
    }

    @Test
    fun closeMovesSelectedAppToFullRootAndRemovesOnlyPickerBases() {
        val fake = FakeShell()
        val split = session(fake)
        val pickers = split.buildPickers()
        split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )

        split.closePickers(PICKERS)

        assertEquals(4, fake.area)
        assertTrue(fake.hasPackage(FULL_ROOT, MUSIC))
        assertFalse(fake.hasActivity(PRIMARY_ROOT, PRIMARY_PICKER_ACTIVITY))
        assertFalse(fake.hasActivity(SECONDARY_ROOT, SECONDARY_PICKER_ACTIVITY))
        assertFalse(fake.commands.any { it.startsWith("service call activity_task 114 ") })
        assertFalse(fake.isGateOpen())
    }

    /**
     * 1.13: a settle is for something that happened.
     *
     * Two pickers already sitting in their own roots settle nothing, and the whole point of the
     * open path finding them alive is not to pay for them again.
     */
    @Test
    fun anOpenThatMovedNothingWaitsForNothing() {
        val fake = FakeShell().apply { liveProductScene() }
        var settled = 0L
        val topology = SplitTopologyCache()
        fake.carChanged += topology::invalidate
        val split = SplitPickerShellSession(
            shell = fake::shell,
            apkPath = SPLIT_APK_PATH,
            settle = { millis -> settled += millis },
            gateLeaseStore = FakeGateLease(),
            topology = topology,
        )

        split.buildPickers()

        assertEquals(
            "nothing moved, nothing was removed, nothing had to settle",
            0L,
            settled,
        )
    }

    /**
     * 1.13.3: the removals are the same exact-identity calls, but they cost one class load, not one
     * each. Loading the proxy dominates a removal by an order of magnitude on this car.
     */
    @Test
    fun clearingSeveralTasksStartsTheProxyOnce() {
        val fake = FakeShell()
        val split = session(fake)
        val pickers = split.buildPickers()
        split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        val before = fake.commands.size

        split.closePickers(PICKERS)

        val proxyCalls = fake.commands.drop(before).filter { it.contains(" remove-task ") }
        assertEquals("one class load, not one per task", 1, proxyCalls.size)
        val groups = proxyCalls.single().substringAfter(" remove-task ").split(' ').chunked(5)
        assertEquals(
            "both picker bases are named in that one call, five arguments each",
            pickers.values.map(Int::toString).sorted(),
            groups.map { group -> group.first() }.sorted(),
        )
        assertTrue(groups.all { group -> group.size == 5 })
    }

    /**
     * Правка W5 волны 7 (приёмочный пропуск DISABLE-sweep): грязный мир добавляет сироту-пикера
     * ПОСЛЕ снапшота `before` - прежняя уборка удаляла только то, что успело попасть в
     * pickerTasks, и порядок гонки решал, выживет ли огрызок. Финальный проход безусловен:
     * свои пикеры по exact identity на всём main display, где бы они ни оказались.
     */
    @Test
    fun closeSweepsAnOwnPickerTheFirstSnapshotNeverSaw() {
        val fake = FakeShell()
        val topology = SplitTopologyCache()
        fake.carChanged += topology::invalidate
        var armed = false
        var injected = false
        val split = SplitPickerShellSession(
            shell = { command ->
                val output = fake.shell(command)
                if (armed && !injected && command == "am stack list") {
                    injected = true
                    // Сирота всплыл между чтениями закрытия: before-снапшот его не видел.
                    fake.addTask(
                        DETACHED_ROOT,
                        LATE_ORPHAN_TASK,
                        SPLIT_HOST_PACKAGE,
                        PRIMARY_PICKER_ACTIVITY,
                    )
                }
                output
            },
            apkPath = SPLIT_APK_PATH,
            settle = {},
            gateLeaseStore = FakeGateLease(),
            topology = topology,
        )
        split.buildPickers()
        armed = true

        split.closePickers(PICKERS)

        assertTrue("сирота действительно всплывал посреди закрытия", injected)
        assertFalse(
            "поздний сирота убран безусловным финальным проходом по identity",
            fake.hasTask(LATE_ORPHAN_TASK),
        )
        assertFalse(fake.hasActivity(PRIMARY_ROOT, PRIMARY_PICKER_ACTIVITY))
        assertFalse(fake.hasActivity(SECONDARY_ROOT, SECONDARY_PICKER_ACTIVITY))
    }

    @Test
    fun disabledProductEndsItsOwnSceneWithoutClosingAGateItNeverOpened() {
        // Обязательство к 1.12 и решение №2: закрывать можно только собственный gate.
        // Завершение сцены - легитимное поведение операции DISABLE (1.2.3), gate - нет.
        val fake = FakeShell(initialGate = true).apply {
            area = 3
            addTask(PRIMARY_ROOT, 40, NAVIGATOR, "$NAVIGATOR.MainActivity")
            addTask(SECONDARY_ROOT, 41, MUSIC, "$MUSIC.MainActivity")
        }

        session(fake).closePickers(PICKERS)

        assertFalse(fake.commands.any { it.startsWith("service call activity_task 114 ") })
        assertFalse(fake.commands.any { it == "service call activity_task 126 i32 0" })
        assertTrue(fake.isGateOpen())
        assertTrue(fake.hasPackage(FULL_ROOT, NAVIGATOR))
        assertTrue(fake.hasPackage(SECONDARY_ROOT, MUSIC))
        assertEquals(4, fake.area)
    }

    @Test
    fun expandedSplitWithStockVacancyBecomesTrueFullscreenWhenProductIsOff() {
        val fake = FakeShell(initialGate = true).apply {
            area = 2
            addTask(
                PRIMARY_ROOT,
                427,
                STOCK_PICKER_PACKAGE,
                STOCK_PICKER_ACTIVITY,
            )
            addTask(
                SECONDARY_ROOT,
                440,
                SPLIT_HOST_PACKAGE,
                SPLIT_APP_HOST_ACTIVITY,
            )
            addTask(SECONDARY_ROOT, 452, NAVIGATOR, "$NAVIGATOR.MainActivity")
        }

        session(fake).closePickers(PICKERS, expectedHostTaskIds = emptySet())

        // The scene ends and our own artifacts go, but the gate stays exactly as we found it.
        assertTrue(fake.isGateOpen())
        assertEquals(4, fake.area)
        assertTrue(fake.hasPackage(FULL_ROOT, NAVIGATOR))
        assertFalse(fake.hasActivity(PRIMARY_ROOT, STOCK_PICKER_ACTIVITY))
        assertFalse(fake.hasActivity(SECONDARY_ROOT, SPLIT_APP_HOST_ACTIVITY))
        assertFalse(fake.commands.any { it.startsWith("service call activity_task 114 ") })
    }

    @Test
    fun closeAcceptsNewFullscreenForegroundWhilePickerCleanupIsInFlight() {
        val fake = FakeShell(
            initialGate = true,
            replaceFullForegroundDuringPickerCleanup = true,
        ).apply {
            area = 2
            addTask(
                PRIMARY_ROOT,
                427,
                STOCK_PICKER_PACKAGE,
                STOCK_PICKER_ACTIVITY,
            )
            addTask(SECONDARY_ROOT, 452, NAVIGATOR, "$NAVIGATOR.MainActivity")
        }

        session(fake).closePickers(PICKERS)

        assertEquals(4, fake.area)
        assertFalse(fake.hasTask(452))
        assertTrue(fake.hasPackage(FULL_ROOT, LAUNCHER_PACKAGE))
        assertFalse(fake.hasActivity(PRIMARY_ROOT, STOCK_PICKER_ACTIVITY))
        assertTrue("a gate this session never opened is not ours to close", fake.isGateOpen())
    }

    @Test
    fun projectedTaskWhosePickerWasClosedReturnsFullscreenInItsRecordedPane() {
        val fake = FakeShell()
        val split = session(fake)
        val pickers = split.buildPickers()
        val placement = split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.PRIMARY),
            target = SplitLaunchTarget(NAVIGATOR, "$NAVIGATOR/$NAVIGATOR.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )

        split.returnRecordedTaskFullscreen(
            SplitPane.PRIMARY,
            placement.appTaskId,
            NAVIGATOR,
        )

        assertEquals(1, fake.area)
        assertEquals("$NAVIGATOR.MainActivity", fake.topActivity(PRIMARY_ROOT))
        assertTrue(fake.commands.any { it == "service call activity_task 114 i32 101" })
    }

    @Test
    fun fullscreenReturnWithWrongTaskIdentityIsANoOp() {
        val fake = FakeShell()
        val split = session(fake)
        split.buildPickers()
        val before = fake.commands.size

        split.returnRecordedTaskFullscreen(SplitPane.PRIMARY, 999, NAVIGATOR)

        assertEquals(3, fake.area)
        assertFalse(
            fake.commands.drop(before).any { it.startsWith("service call activity_task 114 ") },
        )
    }

    @Test
    fun navigationReturnRevealsVerifiedHiddenSplitBeforeChoosingVacantPane() {
        val fake = FakeShell()
        val split = session(fake)
        val pickers = split.buildPickers()
        val navigator = split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.PRIMARY),
            target = SplitLaunchTarget(NAVIGATOR, "$NAVIGATOR/$NAVIGATOR.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        val music = split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        fake.moveTask(navigator.appTaskId, EXTERNAL_ROOT)
        fake.area = 4
        fake.commands.clear()

        val plan = split.prepareNavigationReturn(
            originalRootTaskId = PRIMARY_ROOT,
            pickerComponents = PICKER_COMPONENTS,
            expectedApps = mapOf(
                SplitPane.SECONDARY to SplitPickerExpectedApp(
                    music.appTaskId,
                    music.packageName,
                ),
            ),
        )

        assertFalse(plan.fullscreen)
        assertEquals(SplitPane.PRIMARY, plan.pane)
        assertEquals(PRIMARY_ROOT, plan.rootTaskId)
        assertEquals(pickers.getValue(SplitPane.PRIMARY), plan.hostTaskId)
        assertEquals(3, fake.area)
        assertTrue(fake.commands.any { it == "am task focus ${music.appTaskId}" })
    }

    @Test
    fun navigationReturnDoesNotRevealHiddenSplitWithMismatchedPersistedIdentity() {
        val fake = FakeShell()
        val split = session(fake)
        val pickers = split.buildPickers()
        val navigator = split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.PRIMARY),
            target = SplitLaunchTarget(NAVIGATOR, "$NAVIGATOR/$NAVIGATOR.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        val music = split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        fake.moveTask(navigator.appTaskId, EXTERNAL_ROOT)
        fake.area = 4
        fake.commands.clear()

        val plan = split.prepareNavigationReturn(
            originalRootTaskId = PRIMARY_ROOT,
            pickerComponents = PICKER_COMPONENTS,
            expectedApps = mapOf(
                SplitPane.SECONDARY to SplitPickerExpectedApp(
                    music.appTaskId + 1,
                    music.packageName,
                ),
            ),
        )

        assertTrue(plan.fullscreen)
        assertEquals(4, fake.area)
        assertFalse(fake.commands.any { it.startsWith("am task focus ") })
    }

    @Test
    fun engineCreatesIndependentTaskWhenSamePackageAlreadyLivesInOtherPane() {
        val fake = FakeShell()
        val split = session(fake)
        val pickers = split.buildPickers()
        fake.addTask(PRIMARY_ROOT, 90, MUSIC, "$MUSIC.MainActivity")

        val placement = split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(
                MUSIC,
                "$MUSIC/$MUSIC.MainActivity",
            ),
            pickerComponents = PICKER_COMPONENTS,
        )

        assertTrue(fake.hasTask(90))
        assertTrue(fake.hasPackage(PRIMARY_ROOT, MUSIC))
        assertTrue(fake.hasPackage(SECONDARY_ROOT, MUSIC))
        assertTrue(placement.appTaskId != 90)
        assertEquals(2, fake.taskCount(SECONDARY_ROOT))
    }

    @Test
    fun staleFullscreenTaskReplacedDuringLaunchIsNotProtectedAsPeerWindow() {
        val fake = FakeShell(replaceStaleFullscreenTargetOnLaunch = true)
        val split = session(fake)
        val pickers = split.buildPickers()
        fake.addTask(FULL_ROOT, 90, MUSIC, "$MUSIC.MainActivity")

        val placement = split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )

        assertFalse(fake.hasTask(90))
        assertEquals(SECONDARY_ROOT, fake.taskRoot(placement.appTaskId))
        assertEquals("$MUSIC.MainActivity", fake.topActivity(SECONDARY_ROOT))
        assertFalse(fake.commands.any { it.contains("remove-task ${placement.appTaskId} ") })
    }

    @Test
    fun singleTaskDuplicateIsRejectedWithoutTouchingExistingWindow() {
        val fake = FakeShell()
        val split = session(fake)
        val pickers = split.buildPickers()
        fake.addTask(PRIMARY_ROOT, 90, MUSIC, "$MUSIC.MainActivity")
        fake.commands.clear()

        val error = runCatching {
            split.selectApp(
                pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
                target = SplitLaunchTarget(
                    MUSIC,
                    "$MUSIC/$MUSIC.MainActivity",
                    launchMode = 2,
                ),
                pickerComponents = PICKER_COMPONENTS,
            )
        }.exceptionOrNull()

        assertEquals("Это приложение не поддерживает два окна", error?.message)
        assertTrue(fake.hasTask(90))
        assertEquals(PRIMARY_ROOT, fake.taskRoot(90))
        assertEquals(SECONDARY_PICKER_ACTIVITY, fake.topActivity(SECONDARY_ROOT))
        assertFalse(fake.commands.any { it.startsWith("am start ") })
    }

    @Test
    fun transientPostLaunchAreaDoesNotDeleteSuccessfullyPlacedApp() {
        val fake = FakeShell(transientAreaReadsAfterDirectLaunch = 1)
        val split = session(fake)
        val pickers = split.buildPickers()

        val placement = split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(
                MUSIC,
                "$MUSIC/$MUSIC.MainActivity",
                launchMode = 2,
            ),
            pickerComponents = PICKER_COMPONENTS,
        )

        assertEquals(SECONDARY_ROOT, fake.taskRoot(placement.appTaskId))
        assertEquals("$MUSIC.MainActivity", fake.topActivity(SECONDARY_ROOT))
        assertFalse(fake.commands.any { it.contains("remove-task ${placement.appTaskId} ") })
    }

    @Test(expected = IllegalStateException::class)
    fun engineDoesNotReclaimAppProjectedToAnotherDisplay() {
        val fake = FakeShell().apply {
            addTask(EXTERNAL_ROOT, 91, NAVIGATOR, "$NAVIGATOR.MainActivity")
        }
        val split = session(fake)
        val pickers = split.buildPickers()

        split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.PRIMARY),
            target = SplitLaunchTarget(
                NAVIGATOR,
                "$NAVIGATOR/$NAVIGATOR.MainActivity",
            ),
            pickerComponents = PICKER_COMPONENTS,
        )
    }

    @Test
    fun projectedMemberDoesNotBlockDifferentAppFromVacantPane() {
        // 1.10.2: занять вакансию временным приложением - обычный выбор; спроецирован не он
        val fake = FakeShell().apply {
            addTask(EXTERNAL_ROOT, 91, NAVIGATOR, "$NAVIGATOR.MainActivity")
        }
        val split = session(fake)
        val pickers = split.buildPickers()

        split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )

        assertTrue(fake.hasPackage(EXTERNAL_ROOT, NAVIGATOR))
        assertEquals("$MUSIC.MainActivity", fake.topActivity(SECONDARY_ROOT))
    }

    /**
     * U2 and the identity half of acceptance v17: restoring a standard app must not start a second
     * copy of it.
     *
     * `FLAG_ACTIVITY_MULTIPLE_TASK` on the restore path is what made every open create a fresh
     * Yandex Music behind a splash screen (#44 -> #66 -> #81) and leave the playing one stranded
     * outside the panes. Without it the firmware hands back the task the package already has.
     */
    @Test
    fun restoringAStandardAppReusesItsTaskAndLeavesNoneBehind() {
        val fake = FakeShell().apply {
            area = 4
            // What the last session left running, out of both panel roots.
            addTask(FULL_ROOT, 70, MUSIC, "$MUSIC.MainActivity")
        }

        val built = session(fake).buildScene(
            PICKERS,
            mapOf(SplitPane.PRIMARY to launchTargetOf(MUSIC)),
        )

        assertEquals(emptySet<SplitPane>(), built.failed)
        assertEquals("та же задача, а не новая", 70, built.panes.getValue(SplitPane.PRIMARY).appTaskId)
        assertEquals(PRIMARY_ROOT, fake.taskRoot(70))
        assertEquals("и второй копии не появилось", emptyList<Int>(), fake.taskIds(FULL_ROOT))
        assertEquals(2, fake.taskIds(PRIMARY_ROOT).size)
        assertEquals(1, fake.taskIds(SECONDARY_ROOT).size)
        val launch = fake.commands.single { command ->
            command.startsWith("am start ") && command.contains("'$MUSIC/")
        }
        assertTrue("без MULTIPLE_TASK", launch.contains("-f 0x10200000"))
        assertFalse(fake.commands.any { it.contains("remove-task 70 ") })
    }

    /**
     * The v17 defect class "picker over an application", against правка A4.
     *
     * The firmware can report the app task inside its pane while the picker window is still
     * committed above it. The whole-scene postcondition is one full sample now, so that single
     * sample must refuse a scene whose exact app never becomes the visible top of its root -
     * otherwise the open would commit APP for a pane the user sees as a picker.
     */
    @Test
    fun aBuildRefusesASceneWhosePickerStaysDrawnOverTheApp() {
        val fake = FakeShell(pickerStaysAboveApps = true)

        val refusal = runCatching {
            session(fake).buildScene(
                PICKERS,
                mapOf(SplitPane.PRIMARY to launchTargetOf(NAVIGATOR)),
            )
        }.exceptionOrNull()

        assertNotNull("сцена с пикером поверх приложения не может быть объявлена успехом", refusal)
        assertTrue(
            "и отказ называет ровно этот предикат: ${refusal?.message}",
            refusal?.message.orEmpty().contains("не стало верхним"),
        )
    }

    /**
     * Правка W3 волны 10: задачу, втянутую прошивкой в панель после запусков, выметает сборка.
     *
     * Предпусковая чистка видит мир ДО запусков и до новоприбывших не достаёт, а при открытом gate
     * прошивка втягивает split-способный пакет в панель сама (v21 Д3-Б). Панель обязана остаться
     * «база и приложение», и пользователь обязан получить сцену - а не откат в пустоту по
     * предохранителю «больше двух задач».
     */
    @Test
    fun aTaskTheFirmwarePullsInAfterTheLaunchesLeavesThePaneAlive() {
        val fake = FakeShell().apply {
            addTask(FULL_ROOT, 90, WAZE, "$WAZE.MainActivity")
        }

        val built = session(fake).buildScene(
            pickerComponents = PICKERS,
            targets = mapOf(SplitPane.SECONDARY to launchTargetOf(MUSIC)),
            preexistingTaskIds = setOf(90),
            // Ровно между запусками и чисткой сборки прошивка втягивает чужую задачу в панель.
            onPhase = { phase -> if (phase == "apps-launched") fake.moveTask(90, SECONDARY_ROOT) },
        )

        assertEquals(emptySet<SplitPane>(), built.failed)
        assertEquals(MUSIC, built.panes.getValue(SplitPane.SECONDARY).appPackageName)
        assertTrue("задача пользователя жива", fake.hasTask(90))
        assertEquals("и выселена фоном", FULL_ROOT, fake.taskRoot(90))
        assertEquals(2, fake.taskIds(SECONDARY_ROOT).size)
    }

    /**
     * Правка W3 волны 10 (§1.13): состав панели ожиданием не лечится.
     *
     * Прошивка втягивает задачу уже ПОСЛЕ чистки сборки, и постусловие честно отказывает. Прежде
     * этот отказ переспрашивался двадцать раз по 100 мс - живьём 7.1-7.5 с поверх готового
     * рецепта, из-за которых открытие стоило 11 с. Мир к этому моменту рецепт менять закончил:
     * переспрашивать нечего.
     */
    @Test
    fun aPaneTheFirmwareOverfillsAfterTheSweepIsRefusedWithoutPolling() {
        val fake = FakeShell().apply {
            addTask(FULL_ROOT, 90, WAZE, "$WAZE.MainActivity")
        }
        var commandsAtRefusal = 0

        val refusal = runCatching {
            session(fake).buildScene(
                pickerComponents = PICKERS,
                targets = mapOf(SplitPane.SECONDARY to launchTargetOf(MUSIC)),
                preexistingTaskIds = setOf(90),
                onPhase = { phase ->
                    if (phase == "scene-normalized") {
                        fake.moveTask(90, SECONDARY_ROOT)
                        commandsAtRefusal = fake.commands.size
                    }
                },
            )
        }.exceptionOrNull()

        assertNotNull("панель с тремя задачами не объявляется успехом", refusal)
        assertTrue(
            "и отказ называет ровно этот предикат: ${refusal?.message}",
            refusal?.message.orEmpty().contains("больше двух задач"),
        )
        assertTrue(
            "постусловие ответило с первой пробы, а не с двадцатой: " +
                "${fake.commands.size - commandsAtRefusal} команд",
            fake.commands.size - commandsAtRefusal <= 4,
        )
    }

    /**
     * Invariant 3 and 1.9.2: a build cleans up after the product, and after nobody else.
     *
     * The retired host Activity is the one artefact outside the panes whose ownership a snapshot
     * can prove, because it carries our own component. An ordinary task of the same package is the
     * user's application and is never touched on the strength of its package alone.
     */
    @Test
    fun aBuildRemovesOurOwnStrayHostAndNothingElseOutsideThePanes() {
        val fake = FakeShell().apply {
            addTask(FULL_ROOT, 80, SPLIT_HOST_PACKAGE, SPLIT_APP_HOST_ACTIVITY)
            addTask(FULL_ROOT, 81, WAZE, "$WAZE.MainActivity")
        }

        session(fake).buildPickers()

        assertFalse("огрызок прошлой версии продукта снят", fake.hasTask(80))
        assertTrue("чужое приложение не тронуто", fake.hasTask(81))
        assertEquals(FULL_ROOT, fake.taskRoot(81))
    }

    /**
     * 1.3.2, 1.5.7: the pane whose app did not come back is left on its own picker, and clean.
     *
     * Правка W6: чистота паны не покупается казнью. Задача кандидата жила ДО этой сборки, а сборка
     * без прочитанного прошлого (`preexistingTaskIds == null`) не может доказать, что создала её -
     * значит, задача остаётся жить фоном, а не удаляется (инвариант 3; 1.3.4 - о не-воскрешении,
     * не о казни фоновых задач).
     *
     * Правка W1 волны 10: кандидат стоит ВНЕ панели. Живой кандидат В панели - уже не отказ, а
     * готовое приложение панели, и это отдельный тест ниже.
     */
    @Test
    fun aRestorationThatFailsLeavesThatPaneOnItsPickerAndTheNeighbourAlone() {
        val fake = FakeShell(directTargetLaunchSucceeds = false).apply {
            addTask(FULL_ROOT, 70, NAVIGATOR, "$NAVIGATOR.MainActivity")
        }

        val built = session(fake).buildScene(
            PICKERS,
            mapOf(SplitPane.PRIMARY to launchTargetOf(NAVIGATOR)),
        )

        assertEquals(setOf(SplitPane.PRIMARY), built.failed)
        assertEquals(null, built.panes.getValue(SplitPane.PRIMARY).appTaskId)
        assertTrue("пре-существовавшая задача кандидата жива", fake.hasTask(70))
        assertEquals("и не тронута", FULL_ROOT, fake.taskRoot(70))
        assertEquals(PRIMARY_PICKER_ACTIVITY, fake.topActivity(PRIMARY_ROOT))
        assertEquals("сосед не тронут", SECONDARY_PICKER_ACTIVITY, fake.topActivity(SECONDARY_ROOT))
    }

    /**
     * Правка W1 волны 10 (приёмка v25, Д1): приложение панели уже живёт в ней - его принимают.
     *
     * Прошивке даже не отправляют `am start`: фикстура отказывает любому прямому запуску, и сцена
     * всё равно собирается с этим самым таском. Это и есть требование владельца «если приложение
     * есть - оно должно быть запущено», и одновременно снятие всего класса «запуск поверх живого».
     */
    @Test
    fun aPaneWhoseAppAlreadyLivesInItAdoptsThatTaskInsteadOfLaunching() {
        val fake = FakeShell(directTargetLaunchSucceeds = false).apply {
            addTask(PRIMARY_ROOT, 70, NAVIGATOR, "$NAVIGATOR.MainActivity")
        }

        val built = session(fake).buildScene(
            PICKERS,
            mapOf(SplitPane.PRIMARY to launchTargetOf(NAVIGATOR)),
        )

        assertEquals("панель не провалилась", emptySet<SplitPane>(), built.failed)
        assertEquals(70, built.panes.getValue(SplitPane.PRIMARY).appTaskId)
        assertEquals(PRIMARY_ROOT, fake.taskRoot(70))
        assertTrue(
            "и запуска не было вовсе",
            fake.commands.none { it.startsWith("am start ") && it.contains(NAVIGATOR) },
        )
    }

    /**
     * Живой дефект v25 Д1: у пакета ДВЕ задачи, и обе оказывались в одной панели.
     *
     * Прежде панель шла в запуск (точный записанный id не совпадал), прошивка приносила в корень
     * одну копию, а поиск «максимальный id по всему дисплею» называл другую и втаскивал её следом
     * `promoteTask`-ом. Постусловие видело три задачи в панели и валило всю сборку - открытие
     * стоило 11 с и не оставляло пользователю ничего.
     */
    @Test
    fun aSecondTaskOfTheSamePackageNeitherJoinsThePaneNorFailsTheBuild() {
        val fake = FakeShell().apply {
            addTask(SECONDARY_ROOT, 70, MUSIC, "$MUSIC.MainActivity")
            addTask(FULL_ROOT, 71, MUSIC, "$MUSIC.MainActivity")
        }

        val built = session(fake).buildScene(
            PICKERS,
            mapOf(SplitPane.SECONDARY to launchTargetOf(MUSIC)),
            preexistingTaskIds = setOf(70, 71),
        )

        assertEquals(emptySet<SplitPane>(), built.failed)
        assertEquals(70, built.panes.getValue(SplitPane.SECONDARY).appTaskId)
        assertEquals("вторая копия жива", true, fake.hasTask(71))
        assertEquals("и осталась вне панели", FULL_ROOT, fake.taskRoot(71))
        assertEquals(
            "в панели ровно база и приложение",
            2,
            fake.taskIds(SECONDARY_ROOT).size,
        )
    }

    /**
     * Правка W1 волны 10: копия пакета в корне бережётся только ради запуска.
     *
     * Панель приняла записанную задачу; вторая копия того же пакета, стоящая рядом в том же корне,
     * прежде оставалась жить в панели «про запас» - и панель уходила за предел «база + приложение»,
     * на котором стоит всё постусловие. Второй экземпляр - имущество пользователя: он уезжает
     * живым в фон, а не удаляется.
     */
    @Test
    fun aSecondCopyBesideTheAdoptedAppLeavesThePaneAlive() {
        val fake = FakeShell().apply {
            addTask(SECONDARY_ROOT, 70, MUSIC, "$MUSIC.MainActivity")
            addTask(SECONDARY_ROOT, 71, MUSIC, "$MUSIC.MainActivity")
        }

        val built = session(fake).buildScene(
            PICKERS,
            mapOf(SplitPane.SECONDARY to launchTargetOf(MUSIC)),
            expectedApps = mapOf(SplitPane.SECONDARY to SplitPickerExpectedApp(70, MUSIC)),
            preexistingTaskIds = setOf(70, 71),
        )

        assertEquals(emptySet<SplitPane>(), built.failed)
        assertEquals(70, built.panes.getValue(SplitPane.SECONDARY).appTaskId)
        assertTrue("вторая копия жива", fake.hasTask(71))
        assertEquals("и выселена фоном", FULL_ROOT, fake.taskRoot(71))
        assertEquals(
            "в панели ровно база и приложение",
            2,
            fake.taskIds(SECONDARY_ROOT).size,
        )
    }

    /**
     * Правка W2 волны 10: когда панель всё-таки запускает, её приложение - то, что принёс запуск.
     *
     * Прошивка кладёт этот пакет в панель по своим правилам стороны и переиспользует не самую
     * свежую его задачу. Прежний резолвер брал максимальный id по всему дисплею, называл ЧУЖУЮ
     * копию и втаскивал её в панель - там становилось три задачи.
     */
    @Test
    fun aLaunchIsResolvedByTheTaskItPutInThePaneNotByTheNewestOfThePackage() {
        val fake = FakeShell().apply {
            firmwareChoosesRootFor[MUSIC] = SECONDARY_ROOT
            addTask(FULL_ROOT, 71, MUSIC, "$MUSIC.MainActivity")
            addTask(FULL_ROOT, 70, MUSIC, "$MUSIC.MainActivity")
        }

        val built = session(fake).buildScene(
            PICKERS,
            mapOf(SplitPane.SECONDARY to launchTargetOf(MUSIC)),
            preexistingTaskIds = setOf(70, 71),
        )

        assertEquals(emptySet<SplitPane>(), built.failed)
        assertEquals(
            "панель показывает ту задачу, которую запуск в неё и положил",
            70,
            built.panes.getValue(SplitPane.SECONDARY).appTaskId,
        )
        assertTrue("вторая копия жива", fake.hasTask(71))
        assertEquals("и в панель не попала", FULL_ROOT, fake.taskRoot(71))
        assertEquals(2, fake.taskIds(SECONDARY_ROOT).size)
    }

    @Test
    fun revealedPickerRemovesOnlyTheRecordedDismissedTask() {
        val fake = FakeShell()
        val split = session(fake)
        val pickers = split.buildPickers()
        val placement = split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        fake.promoteActivity(SECONDARY_ROOT, SECONDARY_PICKER_ACTIVITY)

        val observation = split.observePane(SplitPane.SECONDARY, PICKER_COMPONENTS)
        assertTrue(observation.pickerVisible)
        assertTrue(split.removeRecordedTask(placement.appTaskId, MUSIC))
        assertFalse(split.removeRecordedTask(placement.appTaskId, MUSIC))

        assertEquals(SECONDARY_PICKER_ACTIVITY, fake.topActivity(SECONDARY_ROOT))
        assertFalse(fake.hasPackage(SECONDARY_ROOT, MUSIC))
    }

    @Test
    fun recordedTaskRemovalAcceptsAConcurrentExactTaskDisappearance() {
        val fake = FakeShell()
        val split = session(fake)
        val pickers = split.buildPickers()
        val placement = split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        fake.disappearOnNextRemove()

        assertTrue(split.removeRecordedTask(placement.appTaskId, MUSIC))
        assertFalse(fake.hasTask(placement.appTaskId))
    }

    @Test
    fun observingHiddenPickerNeverPrunesVisibleApp() {
        val fake = FakeShell()
        val split = session(fake)
        val pickers = split.buildPickers()
        split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )

        val observation = split.observePane(SplitPane.SECONDARY, PICKER_COMPONENTS)

        assertFalse(observation.pickerVisible)
        assertFalse(observation.nativeHostVisible)
        assertEquals("$MUSIC.MainActivity", fake.topActivity(SECONDARY_ROOT))
        assertTrue(fake.hasPackage(SECONDARY_ROOT, MUSIC))
    }

    @Test
    fun missingPickerIsNeverRebuiltByObservation() {
        val fake = FakeShell()
        val split = session(fake)
        val pickers = split.buildPickers()
        split.selectApp(
            pickerTaskId = pickers.getValue(SplitPane.SECONDARY),
            target = SplitLaunchTarget(MUSIC, "$MUSIC/$MUSIC.MainActivity"),
            pickerComponents = PICKER_COMPONENTS,
        )
        fake.removeActivity(SECONDARY_ROOT, SECONDARY_PICKER_ACTIVITY)

        val beforeCommands = fake.commands.size
        val observation = split.observePane(SplitPane.SECONDARY, PICKER_COMPONENTS)

        assertEquals(null, observation.hostTaskId)
        assertFalse(fake.hasActivity(SECONDARY_ROOT, SECONDARY_PICKER_ACTIVITY))
        assertEquals("$MUSIC.MainActivity", fake.topActivity(SECONDARY_ROOT))
        assertFalse(
            fake.commands.drop(beforeCommands).any {
                it.startsWith("am start ") || it.contains("replace-task-base ")
            },
        )
    }

    @Test
    fun disabledProductClosesOnlyTheGateItsOwnLeaseHolds() {
        val ownedFake = FakeShell()
        val ownedLease = FakeGateLease()
        val ownedSession = session(ownedFake, ownedLease)
        ownedSession.buildPickers()

        assertTrue(ownedLease.isOwned())
        assertTrue(ownedFake.isGateOpen())

        ownedSession.closePickers(PICKERS)

        assertFalse(ownedLease.isOwned())
        assertFalse(ownedFake.isGateOpen())

        // Exact DiLink 5.1 contract: tx123 is a split-capability answer and remains true even
        // after tx126(false). It must never be used as a mutable gate postcondition.
        val externalFake = FakeShell(
            initialGate = true,
            capabilityAlwaysTrue = true,
        )
        val externalLease = FakeGateLease()
        val externalSession = session(externalFake, externalLease)
        externalSession.buildPickers()
        externalSession.closePickers(PICKERS)

        assertFalse(externalLease.isOwned())
        assertFalse(externalFake.isGateOpen())
        assertTrue(externalFake.commands.any { it == "service call activity_task 126 i32 0" })

        // And the case the product used to get wrong: a session that never took the lease has no
        // business touching the global gate, whatever else the toggle-off has to clean up.
        val foreignFake = FakeShell(initialGate = true).apply {
            area = 3
            addTask(PRIMARY_ROOT, 40, STOCK_PICKER_PACKAGE, STOCK_PICKER_ACTIVITY)
            addTask(SECONDARY_ROOT, 41, MUSIC, "$MUSIC.MainActivity")
        }
        val foreignLease = FakeGateLease()

        session(foreignFake, foreignLease).closePickers(PICKERS)

        assertFalse(foreignLease.isOwned())
        assertTrue(foreignFake.isGateOpen())
        assertFalse(foreignFake.commands.any { it == "service call activity_task 126 i32 0" })
    }

    @Test
    fun openingSessionAssertsGateEvenWhenCapabilityIsAlwaysTrue() {
        val fake = FakeShell(
            initialGate = false,
            capabilityAlwaysTrue = true,
        )

        session(fake).buildPickers()

        assertTrue(fake.isGateOpen())
        assertTrue(fake.commands.any { it == "service call activity_task 126 i32 1" })
    }

    @Test
    fun homeSuspendsOwnedGateButKeepsLeaseForExplicitReopen() {
        val fake = FakeShell()
        val lease = FakeGateLease()
        val split = session(fake, lease)
        split.buildPickers()
        fake.area = 0

        assertTrue(split.suspendOwnedGateForHome())

        assertFalse(fake.isGateOpen())
        assertTrue(lease.isOwned())

        split.buildPickers()

        assertTrue(fake.isGateOpen())
        assertTrue(lease.isOwned())
    }

    /**
     * Правка W5 (1.9.3, диагноз v21 Д3-Б): прежние шесть проб по 100 мс сдавались тихо, gate
     * оставался открыт над накрытой сценой, и прошивка сама втягивала следующий запуск в
     * широкую панель. Подтверждение area==0 обязано ретраиться до ~3 с.
     */
    @Test
    fun homeSuspendOutlastsASlowHomeTransition() {
        val fake = FakeShell(initialGate = true).apply { area = 3 }
        val lease = FakeGateLease(owned = true)
        var pauses = 0
        val split = SplitPickerShellSession(
            shell = fake::shell,
            apkPath = "/data/app/dev.denza.apps/base.apk",
            settle = {
                pauses += 1
                if (pauses == 15) fake.area = 0
            },
            gateLeaseStore = lease,
        )

        assertTrue("подтверждение пережило медленный переход", split.suspendOwnedGateForHome())
        assertFalse(fake.isGateOpen())
        assertTrue("аренда остаётся для явного возобновления", lease.isOwned())
        assertTrue(
            "ретраев больше прежних шести проб",
            fake.commands.count { it == "service call activity_task 30" } > 6,
        )
    }

    /**
     * Правка W3 (волна 7, карта tx30 живьём 2026-08-25): чужое fullscreen-окно поверх (area 4)
     * накрывает сцену так же честно, как Home (1.11.5), и в переходном грязном мире area
     * дребезжит 0↔4, не обязуясь остановиться на нуле. Жёсткое ==0 сжигало весь бюджет над
     * честно накрытой сценой и оставляло gate открытым - подтверждение обязано быть предикатом
     * накрытия.
     */
    @Test
    fun homeSuspendConfirmsOnTheCoverPredicateNotOnAreaZeroAlone() {
        val fake = FakeShell(initialGate = true).apply { area = 3 }
        val lease = FakeGateLease(owned = true)
        var pauses = 0
        val split = SplitPickerShellSession(
            shell = fake::shell,
            apkPath = "/data/app/dev.denza.apps/base.apk",
            settle = {
                pauses += 1
                // Переход дребезжит и оседает на 4: нуля этот мир не покажет никогда.
                if (pauses == 2) fake.area = 4
            },
            gateLeaseStore = lease,
        )

        assertTrue("накрытие подтверждено без единого area==0", split.suspendOwnedGateForHome())
        assertFalse(fake.isGateOpen())
        assertTrue("аренда остаётся для явного возобновления", lease.isOwned())
        assertEquals(
            "подтверждение остановилось на первом накрытом чтении",
            3,
            fake.commands.count { it == "service call activity_task 30" },
        )
    }

    @Test
    fun homeSuspendExhaustsItsBudgetWithoutClosingBlind() {
        val fake = FakeShell(initialGate = true).apply { area = 3 }
        val lease = FakeGateLease(owned = true)

        assertFalse(session(fake, lease).suspendOwnedGateForHome())

        assertTrue("gate не закрыт вслепую", fake.isGateOpen())
        assertTrue(lease.isOwned())
        val reads = fake.commands.count { it == "service call activity_task 30" }
        assertTrue("ретраи ограничены бюджетом ~3 с: $reads чтений", reads in 7..40)
    }

    /** Правка W5, §4: явный ввод пользователя не ждёт ретраи - suspend отдаёт воркер сразу. */
    @Test
    fun homeSuspendYieldsToWaitingUserInput() {
        val fake = FakeShell(initialGate = true).apply { area = 3 }
        val lease = FakeGateLease(owned = true)

        assertFalse(session(fake, lease).suspendOwnedGateForHome(displaced = { true }))

        assertEquals(
            "ровно одно чтение: воркер отдан немедленно",
            1,
            fake.commands.count { it == "service call activity_task 30" },
        )
        assertTrue(fake.isGateOpen())
        assertTrue(lease.isOwned())
    }

    @Test
    fun homeNeverClosesGateOwnedByAnotherComponent() {
        val fake = FakeShell(initialGate = true).apply { area = 0 }
        val lease = FakeGateLease(owned = false)

        assertFalse(session(fake, lease).suspendOwnedGateForHome())

        assertTrue(fake.isGateOpen())
        assertFalse(fake.commands.any { it == "service call activity_task 126 i32 0" })
    }

    @Test
    fun nonHomeEventNeverSuspendsOwnedGate() {
        val fake = FakeShell(initialGate = true).apply { area = 3 }
        val lease = FakeGateLease(owned = true)

        assertFalse(session(fake, lease).suspendOwnedGateForHome())

        assertTrue(fake.isGateOpen())
        assertTrue(lease.isOwned())
    }

    /**
     * Правка волны 17: та же подвеска для того, кто уже смотрит на мир и не имеет права ждать в
     * нём (сверка). Одно чтение - один ответ; аренда остаётся, как и у Home.
     */
    @Test
    fun singleShotSuspendClosesTheOwnedGateOverACoveredScene() {
        val fake = FakeShell(initialGate = true).apply { area = 0 }
        val lease = FakeGateLease(owned = true)

        assertTrue(session(fake, lease).suspendOwnedGateIfCovered())

        assertFalse(fake.isGateOpen())
        assertTrue("аренда остаётся для явного возобновления", lease.isOwned())
        assertEquals(
            "ровно одно чтение: ждать здесь нечего",
            1,
            fake.commands.count { it == "service call activity_task 30" },
        )
    }

    /** Чужое fullscreen-окно накрывает сцену так же честно, как Home (1.11.5). */
    @Test
    fun singleShotSuspendAcceptsAForeignFullscreenCoverToo() {
        val fake = FakeShell(initialGate = true).apply { area = 4 }

        assertTrue(session(fake, FakeGateLease(owned = true)).suspendOwnedGateIfCovered())

        assertFalse(fake.isGateOpen())
    }

    /** Ложное закрытие строго хуже пропущенного: над видимой сценой не отправляется ничего. */
    @Test
    fun singleShotSuspendNeverTouchesTheGateOverAVisibleScene() {
        val fake = FakeShell(initialGate = true).apply { area = 3 }
        val lease = FakeGateLease(owned = true)

        assertFalse(session(fake, lease).suspendOwnedGateIfCovered())

        assertTrue(fake.isGateOpen())
        assertTrue(lease.isOwned())
        assertFalse(fake.commands.any { it == "service call activity_task 126 i32 0" })
    }

    /** Правило закрытия одно и то же: gate, который открыли не мы, не наш (инвариант 1, 1.12). */
    @Test
    fun singleShotSuspendNeverClosesAGateOwnedByAnotherComponent() {
        val fake = FakeShell(initialGate = true).apply { area = 0 }

        assertFalse(session(fake, FakeGateLease(owned = false)).suspendOwnedGateIfCovered())

        assertTrue(fake.isGateOpen())
        assertFalse(fake.commands.any { it == "service call activity_task 30" })
        assertFalse(fake.commands.any { it == "service call activity_task 126 i32 0" })
    }

    @Test
    fun ownedGateIsRecoveredEvenWhenPickerTasksHaveAlreadyGone() {
        val fake = FakeShell(initialGate = true)
        val lease = FakeGateLease(owned = true)

        session(fake, lease).closePickers(PICKERS)

        assertFalse(fake.isGateOpen())
        assertFalse(lease.isOwned())
    }

    @Test
    fun closeRemovesPickerStrandedOutsideNativeRootsAfterPartialOpen() {
        val fake = FakeShell(initialGate = true).apply {
            addTask(
                FULL_ROOT,
                77,
                "dev.denza.apps",
                PRIMARY_PICKER_ACTIVITY,
            )
        }
        val lease = FakeGateLease(owned = true)

        session(fake, lease).closePickers(PICKERS)

        assertFalse(fake.hasActivity(FULL_ROOT, PRIMARY_PICKER_ACTIVITY))
        assertFalse(fake.isGateOpen())
        assertFalse(lease.isOwned())
    }

    /**
     * Production always hands the session a gate lease, because the gate is firmware-global and the
     * only rule that may close it is "we opened it". A test that wants to prove the product keeps
     * its hands off someone else's gate therefore passes a lease that owns nothing.
     */
    private fun session(
        fake: FakeShell,
        gateLeaseStore: SplitGateLeaseStore = FakeGateLease(),
    ): SplitPickerShellSession {
        // One session shares its two topology reads for as long as nothing could have moved a
        // task. In the car that means "no command and no settle pause since"; here it additionally
        // means "and the test did not reach into the firmware behind the session's back".
        val topology = SplitTopologyCache()
        fake.carChanged += topology::invalidate
        return SplitPickerShellSession(
            shell = fake::shell,
            apkPath = "/data/app/dev.denza.apps/base.apk",
            settle = {},
            gateLeaseStore = gateLeaseStore,
            topology = topology,
        )
    }

    private fun intParcel(value: Int): String =
        "Result: Parcel(00000000 ${"%08x".format(value)} '........')"

    private companion object {
        /** Сирота правки W5: собственный пикер, всплывший после before-снапшота закрытия. */
        const val LATE_ORPHAN_TASK = 555
    }
}
