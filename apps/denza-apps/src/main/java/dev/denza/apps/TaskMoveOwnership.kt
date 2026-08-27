package dev.denza.apps

/**
 * Кто прямо сейчас имеет право двигать задачи на головном устройстве.
 *
 * Split, Simulcast и навигация на приборке перестраивают одно и то же дерево задач, каждый своим
 * рецептом, и одновременная перестройка двумя из них - это не два независимых результата, а один
 * непредсказуемый. Владелец в каждый момент ровно один; вторая функция, пока совместное владение не
 * доказано, отказывается закрыто - не двигает задачи и оставляет экран рабочим, без объяснений
 * (контракт §11.21, инвариант 2).
 *
 * До этого класса координация была двумя эвристиками в ядре split: пятисекундное окно
 * `bypassExternalTaskMoves` и долгий `hold`/`release`. Обе жили внутри `SplitCoordinatorCore`,
 * то есть пропадали вместе с ним (`core?.` - тихий no-op на незапущенном split), обе спрашивались
 * только на `EDGE` и `RECONCILE`, и обратной гарантии - «а можно ли Simulcast'у начать move» - не
 * было вовсе.
 */
enum class TaskMoveOwner {
    SPLIT,
    SIMULCAST,
    NAVIGATION,
}

/**
 * Владение, выданное одному владельцу. Отпускается явно; [TaskMoveOwnership] всё равно держит
 * дедлайн, потому что отпустить может быть некому.
 */
class TaskMoveLease internal constructor(
    private val ownership: TaskMoveOwnership,
    val owner: TaskMoveOwner,
    private val serial: Long,
) {
    /** Идемпотентно и по владельцу: чужое владение этот вызов не снимает никогда. */
    fun release() {
        ownership.release(owner, serial)
    }
}

/**
 * Атомарный единственный владелец с дедлайном.
 *
 * Дедлайн здесь не таймаут вежливости, а единственная защита от навсегда закрытого отказа:
 * владение отпускают колбэки чужих подсистем (мост DiShare, события accessibility), и колбэк,
 * который не пришёл, не должен запирать функцию до перезапуска процесса. Поэтому владение всегда
 * берётся на срок, а `release` - это «раньше срока», а не «единственный способ».
 *
 * Серийный номер меняется только при СМЕНЕ владельца. Повторный `acquire` тем же владельцем
 * продлевает срок и возвращает тот же серийный номер, поэтому взятый ранее lease продолжает быть
 * действительным и его `release` действительно отпускает владение, а не молча промахивается.
 */
class TaskMoveOwnership(private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L }) {

    private val lock = Any()
    private var owner: TaskMoveOwner? = null
    private var expiresAtMs = 0L
    private var serial = 0L

    /**
     * @param holdMs на сколько владение берётся, если никто его не отпустит.
     * @param preempt отбирать ли владение у другого владельца. Так поступает только выключение
     * тумблера: разбор сцены - это отказ от владения, и ждать чужого разрешения на него нельзя
     * (контракт 1.2, сценарий §11.31).
     * @return выданное владение либо `null`, если оно у другого владельца.
     */
    fun acquire(
        owner: TaskMoveOwner,
        holdMs: Long,
        preempt: Boolean = false,
    ): TaskMoveLease? = synchronized(lock) {
        val now = nowMs()
        val holder = liveHolder(now)
        if (holder != null && holder != owner && !preempt) return null
        if (holder != owner) serial += 1
        this.owner = owner
        expiresAtMs = maxOf(if (holder == owner) expiresAtMs else 0L, now + holdMs)
        TaskMoveLease(this, owner, serial)
    }

    /** Занято ли владение кем-то ДРУГИМ. Свободное и своё - оба «нет». */
    fun heldByOther(owner: TaskMoveOwner): Boolean = synchronized(lock) {
        val holder = liveHolder(nowMs())
        holder != null && holder != owner
    }

    /** Кто владеет прямо сейчас, для журнала. */
    fun holder(): TaskMoveOwner? = synchronized(lock) { liveHolder(nowMs()) }

    internal fun release(owner: TaskMoveOwner, serial: Long) = synchronized(lock) {
        if (this.owner == owner && this.serial == serial) {
            this.owner = null
            expiresAtMs = 0L
        }
    }

    private fun liveHolder(now: Long): TaskMoveOwner? {
        if (owner != null && now >= expiresAtMs) {
            owner = null
            expiresAtMs = 0L
        }
        return owner
    }

    companion object {
        /**
         * Общий на процесс. Владение переживает пересоздание координатора split: пока его ядро
         * умирало и рождалось, чужой move продолжался.
         */
        @JvmField
        val shared = TaskMoveOwnership()

        /**
         * Короткое владение «я сейчас двигаю, не мешай», без явного отпускания - ровно то, чем был
         * прежний `bypassExternalTaskMoves`. Возвращаемое значение сознательно игнорируемо: у
         * пульса нет ветки отказа, он либо продлевает своё владение, либо не получает чужого.
         */
        @JvmStatic
        fun pulse(owner: TaskMoveOwner) {
            shared.acquire(owner, PULSE_MS)
        }

        /** Пятисекундное окно прежнего `bypassExternalTaskMoves`, число не менялось. */
        const val PULSE_MS = 5_000L

        /**
         * Потолок долгого владения. Больше самого долгого пользовательского бюджета split (10 с),
         * потому что переброс на приборку и запуск моста DiShare - не пользовательские операции и
         * под потолок §1.13 не подпадают.
         */
        const val HANDOFF_MS = 30_000L
    }
}
