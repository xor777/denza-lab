#!/usr/bin/env python3
"""
Emit the two boards that describe the system, from the system itself.

The old kit was the worst offender in the audit it was supposed to prevent: a
page declaring "nothing smaller than 15 px" set in 12, 13 and 14; a regeneration
arc drawn 24 pixels off its own circle on the sheet other boards copy from; and
two specimen cards stretched with preserveAspectRatio="none", so the primitives
being documented were the one place they were shown distorted.

Nothing here is drawn by hand. The ramps, the rhythm, the radii and the icon
weight are the values the boards are built from, printed; the gauge specimens
come from gen_cluster, the same function the cluster and panel boards call.
"""
import gen_cluster as g

BG, SURFACE, RAISED = '#07080A', '#212429', '#323538'
INK, INK2, MUTED, DEEP = '#DAE1EB', '#C5CDD9', '#86909B', '#6E767F'
ACCENT, PEAK, RETURN, RETURN_INK = '#FEEFAB', '#FFF8DA', '#2D82D7', '#4B9BE0'
WARNING, DANGER, TRACK, MARK = '#FF9F19', '#FF4046', '#22262E', '#3F434D'

IVI_RAMP = [82, 62, 46, 34, 24, 19, 15]
IVI_STEP = 6
RADII = [22, 12, 6, 2]
ICON_OPTICAL = 2.0
ICON_SIZES = [44, 30, 20]

HEAD = """<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <script src="./support.js"></script>
</head>
<body>
<x-dc>
<helmet>
  <link href="https://fonts.googleapis.com/css2?family=Roboto:wght@200;300;400;500&amp;family=Roboto+Mono:wght@300;400;500&amp;display=swap" rel="stylesheet">
  <style>
    body { margin:0; background:#07080A; font-family:'Roboto','Segoe UI',system-ui,sans-serif; }
    a { color:#FEEFAB; } a:hover { color:#FFF7D2; }
    .h { font-size:15px; letter-spacing:0.12em; font-weight:500; color:#86909B; }
    .note { font-size:19px; color:#6E767F; line-height:1.5; }
    .card { background:#212429; border:1px solid rgba(218,225,235,0.10); border-radius:22px; padding:24px; }
    .mono { font-family:'Roboto Mono',monospace; font-weight:300; color:#DAE1EB; }
    .spec { font-family:'Roboto Mono',monospace; font-size:15px; color:#6E767F; }
    .fg { font-family:'Roboto Mono',monospace; font-weight:300; font-size:62px; fill:#DAE1EB; }
    .rd { font-family:'Roboto Mono',monospace; font-weight:300; font-size:34px; fill:#DAE1EB; }
    .un { font-size:19px; fill:#86909B; }
    .bd { font-size:19px; fill:#6E767F; }
    .tk { font-family:'Roboto Mono',monospace; font-size:15px; fill:#6E767F; }
  </style>
</helmet>
"""


def card(title, note, body):
    return (f'<div class="card" style="display:flex; flex-direction:column; gap:18px;">'
            f'<div class="h">{title}</div>{body}'
            f'<div class="note">{note}</div></div>')


def ramp_rows(ramp, label, unit):
    rows = []
    for i, size in enumerate(ramp):
        ratio = f'{ramp[i - 1] / size:.2f}x' if i else '—'
        rows.append(
            f'<div style="display:flex; align-items:baseline; gap:24px;">'
            f'<div class="spec" style="width:120px;">{size:g} {unit}</div>'
            f'<div class="spec" style="width:80px;">{ratio}</div>'
            f'<div class="mono" style="font-size:{size}px; line-height:1;">1321</div></div>')
    return (f'<div style="display:flex; flex-direction:column; gap:14px;">'
            f'<div class="spec" style="color:#86909B;">{label}</div>'
            + ''.join(rows) + '</div>')


def rhythm_row(step, count=6):
    cells = ''.join(
        f'<div style="display:flex; flex-direction:column; align-items:center; gap:8px;">'
        f'<div style="width:{step * n}px; height:12px; border-radius:2px; '
        f'background:rgba(254,239,171,0.30);"></div>'
        f'<div class="spec">{n}</div></div>' for n in range(1, count + 1))
    return f'<div style="display:flex; align-items:flex-end; gap:24px;">{cells}</div>'


def radius_row():
    cells = ''.join(
        f'<div style="display:flex; flex-direction:column; align-items:center; gap:10px;">'
        f'<div style="width:84px; height:84px; border-radius:{r}px; background:{RAISED};"></div>'
        f'<div class="spec">{r}</div></div>' for r in RADII)
    pill = ('<div style="display:flex; flex-direction:column; align-items:center; gap:10px;">'
            f'<div style="width:84px; height:14px; border-radius:7px; background:{RAISED}; '
            'margin-bottom:70px;"></div>'
            '<div class="spec" style="margin-top:-80px;">трек</div></div>')
    return f'<div style="display:flex; gap:24px; align-items:flex-start;">{cells}{pill}</div>'


ICON = ('<circle cx="12" cy="12" r="9"></circle><circle cx="12" cy="12" r="3"></circle>'
        '<path d="M3.2 10.5 L9.1 10.9 M20.8 10.5 L14.9 10.9 M12 15 L12 21"></path>')


def icon_row():
    cells = ''
    for size in ICON_SIZES:
        sw = round(ICON_OPTICAL * 24 / size, 3)
        cells += (f'<div style="display:flex; flex-direction:column; align-items:center; gap:10px;">'
                  f'<div style="height:48px; display:flex; align-items:center;">'
                  f'<svg width="{size}" height="{size}" viewBox="0 0 24 24" fill="none" '
                  f'stroke="{ACCENT}" stroke-width="{sw:g}" stroke-linecap="round">{ICON}</svg></div>'
                  f'<div class="spec">{size} · {sw:g}</div></div>')
    return f'<div style="display:flex; gap:36px; align-items:flex-end;">{cells}</div>'


def swatch(name, value, source):
    ink = '#262D33' if value in (ACCENT, PEAK) else INK
    return (f'<div style="display:flex; flex-direction:column; gap:10px; min-width:0;">'
            f'<div style="height:84px; border-radius:12px; background:{value}; '
            f'display:flex; align-items:flex-end; padding:10px 12px; box-sizing:border-box; '
            f'color:{ink}; font-size:15px; font-family:\'Roboto Mono\',monospace;">{value}</div>'
            f'<div style="font-size:19px; color:{INK2};">{name}</div>'
            f'<div class="spec" style="line-height:1.4; word-break:break-all;">{source}</div></div>')


ROLES = [
    ('Интерфейс', ACCENT, 'vc_denza_progress_blue'),
    ('Данные прибора', INK, 'qs_icon_text_denza'),
    ('Энергия обратно', RETURN, 'sys_color_function'),
    ('Внимание', WARNING, 'sys_color_abnormal'),
    ('Неисправность', DANGER, 'sys_color_warning'),
    ('Трек', TRACK, 'производная от sys_gray_800'),
]

TOKENS = [
    ('Фон', '#07080A', 'sys_gray_900'),
    ('Поверхность выключенного', '#15181F', 'sys_gray_800'),
    ('Поверхность', '#212429', 'qs_panel_start_color_bg_denza'),
    ('Поверхность приподнятая', '#323538', 'scene_mode_button_bg_normal_denza'),
    ('Поверхность высокая', '#484E55', 'qs_adjust_icon_tint_color_denza'),
    ('Чернила', '#DAE1EB', 'qs_icon_text_denza'),
    ('Чернила вторичные', '#C5CDD9', 'qs_adjust_text_color_denza'),
    ('Приглушённый', '#86909B', 'qs_adjust_seekbar_text_color_denza'),
    ('Приглушённый глубокий', '#6E767F', 'производная'),
    ('На акценте', '#262D33', 'qs_icon_on_denza'),
    ('Акцент', '#FEEFAB', 'vc_denza_progress_blue'),
    ('Пик данных', '#FFF8DA', 'производная от акцента'),
    ('Возврат энергии', '#2D82D7', 'sys_color_function'),
    ('Возврат в тексте', '#4B9BE0', 'производная'),
    ('Внимание', '#FF9F19', 'sys_color_abnormal'),
    ('Неисправность', '#FF4046', 'sys_color_warning / sys_red_400'),
    ('Трек', '#22262E', 'производная'),
    ('Метка на треке', '#3F434D', 'производная'),
]

REST = [
    ('График до первого участка', 'стоим · считаю расход',
     'Пустая нулевая линия внутри циферблата читается как сломанный график, '
     'а не как график, которому пока нечего сказать. Он не рисуется вовсе.'),
    ('Двигатель заглушен', 'заглушен · 491 км на бензине',
     'Одно слово «заглушен» тратит строку впустую. Строка несёт то, чего стоит '
     'заглушенный двигатель.'),
    ('Идёт заряд', 'заряжается · осталось 2 ч 15 мин',
     'Мощность заряда не повторяется: она уже стоит в центре циферблата, '
     'потому что заряд рисуется как приходящая энергия.'),
    ('Лампы не ответили', 'жидкости не ответили',
     'Лампа, которая не отчиталась, — не то же самое, что лампа с хорошей '
     'новостью. Полый кружок, а не залитый.'),
]

NOT_DRAWN = [
    ('Заряд батареи', 'штатная приборка в нескольких сантиметрах'),
    ('Запас хода', 'там же'),
    ('Мощность заряда', 'центр циферблата уже её показывает'),
    ('Бортовые 12 В', 'диагностика, есть на странице машины'),
]


def kit():
    cluster_ramp = ramp_rows(
        [52, 34, 24, 18, 13, 11],
        'ПРИБОРКА ВОДИТЕЛЯ · виртуальные единицы, масштаб 1,70 на панели', 'ед')
    ivi_ramp = ramp_rows(IVI_RAMP, 'ГОЛОВНОЕ УСТРОЙСТВО · пиксели 1:1', 'px')

    # The specimens sit at the two ends of the scale rather than at a comfortable
    # reading: three digits is the widest the dial can ever be asked to show, and
    # a kit that only ever shows 34 kW is a kit that has not been asked the
    # question.
    gauge_wide = g.gauge_block(560, 280, 214, 200, g.PANEL, 300.0, g.DRIVING_BARS,
                               'полная шкала расхода')
    gauge_small = g.gauge_block(360, 180, 138, 128, g.PANEL_NARROW, -100.0, g.DRIVING_BARS,
                                'полная рекуперация')

    rest = ''.join(
        f'<div style="display:flex; flex-direction:column; gap:10px;">'
        f'<div class="spec" style="color:#86909B;">{t}</div>'
        f'<div style="font-size:24px; color:{INK};">{line}</div>'
        f'<div class="note" style="font-size:15px;">{why}</div></div>'
        for t, line, why in REST)

    not_drawn = ''.join(
        f'<div style="display:flex; align-items:baseline; gap:18px;">'
        f'<div style="font-size:24px; color:{DEEP}; text-decoration:line-through; '
        f'width:280px;">{what}</div>'
        f'<div class="note" style="font-size:15px;">{why}</div></div>'
        for what, why in NOT_DRAWN)

    roles = ''.join(
        f'<div style="display:flex; flex-direction:column; gap:10px;">'
        f'<div style="height:64px; border-radius:12px; background:{v};"></div>'
        f'<div style="font-size:19px; color:{INK2};">{n}</div>'
        f'<div class="spec">{s}</div></div>' for n, v, s in ROLES)

    body = f'''<div style="width:1960px; box-sizing:border-box; background:{BG}; padding:44px;
  display:flex; flex-direction:column; gap:30px;">

  <div style="display:flex; align-items:baseline; gap:24px;">
    <div style="font-size:46px; font-weight:200; color:{INK};">Кит</div>
    <div class="note">Всё, из чего собраны остальные доски. Размер, которого нет на лестнице,
    приложению недоступен.</div>
  </div>

  <div style="display:grid; grid-template-columns:1fr 1fr; gap:30px;">
    {card('ДВЕ ЛЕСТНИЦЫ КЕГЛЕЙ',
          'Две, а не одна: приборка — 320 dpi в семидесяти сантиметрах, головное устройство — '
          'другой экран на другом расстоянии. Общее у них правило, а не числа. Соседние ступени '
          'нигде не ближе 1,18x — разницу, которую можно измерить и нельзя увидеть, лестница не '
          'держит: ступень 16 против 18 пробовали и убрали.',
          f'<div style="display:flex; gap:44px;">{cluster_ramp}{ivi_ramp}</div>')}
    {card('РИТМ И СКРУГЛЕНИЯ',
          f'Любой отступ — целое число шагов: {IVI_STEP} px на головном устройстве, 8 и 6 единиц '
          'на приборке. Скруглений четыре, плюс трек, у которого радиус всегда половина высоты. '
          'Рамка всегда одна и та же — выделение несёт заливка и чернила, а не толщина края: '
          'выделенная карточка в два пикселя выше соседних сдвигает весь ряд.',
          f'{rhythm_row(IVI_STEP)}{radius_row()}')}
  </div>

  <div style="display:grid; grid-template-columns:1fr 1fr; gap:30px;">
    {card('ИКОНКИ · ОДНА ОПТИЧЕСКАЯ ТОЛЩИНА',
          f'Толщина обводки берётся из размера: {ICON_OPTICAL:g} × 24 ÷ размер. Иначе один и тот '
          'же глиф читается тяжелее на плитке, чем в шторке, — так и вышло, тринадцать разных '
          'оптических толщин на одно плоское семейство.',
          icon_row())}
    {card('ЦВЕТ ПО РОЛЯМ',
          'Шампань — только интерфейс: тёплый жёлтый в машине уже значит «осторожно», и акцент, '
          'спорящий с предупреждением, стоит водителю взгляда. Данные приборов — чернила. '
          'Энергия, уходящая обратно в батарею, — синий: рекуперация и генерация двигателем это '
          'одно событие. Зелёного нет: норма должна молчать.',
          f'<div style="display:grid; grid-template-columns:repeat(6, minmax(0, 1fr)); '
          f'gap:18px;">{roles}</div>')}
  </div>

  {card('ПРИБОР ЭНЕРГИИ · ОДИН КОМПОНЕНТ',
        'Дуга, вложенный график и цифра — одно целое, потому что вместе они отвечают на один '
        'вопрос. Стороны получают одинаковую дугу и разный размах — 300 кВт наружу против '
        'примерно 100 обратно, — поэтому метки подписаны: пара 60 и 20 на одинаковом отклонении '
        'говорит и про масштаб, и про то, что это не спидометр. Ноль не подписан: там заливка '
        'исчезает. Образцы стоят на концах шкалы: три знака — самое широкое, что цифра может '
        'показать, и она проходит внутри дуги с запасом. Знака минуса нет — направление уже '
        'сказано дважды, стороной и цветом, а четвёртый знак съедал бы весь запас.',
        f'<div style="display:flex; gap:44px; align-items:flex-end;">{gauge_wide}{gauge_small}</div>')}

  <div style="display:grid; grid-template-columns:1fr 1fr; gap:30px;">
    {card('СОСТОЯНИЯ ПОКОЯ',
          'Большую часть времени ничего не происходит, и это отдельные состояния, а не общий '
          '«нет данных». После действия — сцена или рабочий вид, третьего нет.',
          f'<div style="display:flex; flex-direction:column; gap:24px;">{rest}</div>')}
    {card('ЧЕГО МЫ НЕ РИСУЕМ',
          'Правило одно: не повторять то, что машина уже показывает сама. Оно же убрало '
          'мощность заряда и бортовые вольты, когда те попробовали вернуться.',
          f'<div style="display:flex; flex-direction:column; gap:18px;">{not_drawn}</div>')}
  </div>
</div>
</x-dc>
<script data-dc-script data-props='{{"$preview":{{"width":1960,"height":1740}}}}'>
class Component extends DCLogic {{ renderVals() {{ return {{}}; }} }}
</script>
</body>
</html>
'''
    return HEAD + body


def palette():
    rows = ''.join(
        f'<div style="display:flex; align-items:center; gap:24px;">'
        f'<div style="width:120px; height:56px; border-radius:12px; background:{v}; '
        f'flex-shrink:0;"></div>'
        f'<div class="mono" style="font-size:19px; width:120px; flex-shrink:0;">{v}</div>'
        f'<div style="font-size:19px; color:{INK2}; width:300px; flex-shrink:0;">{n}</div>'
        f'<div class="spec" style="font-size:19px;">{s}</div></div>'
        for n, v, s in TOKENS)
    return HEAD + f'''<div style="width:1180px; box-sizing:border-box; background:{BG}; padding:44px;
  display:flex; flex-direction:column; gap:30px;">

  <div style="display:flex; flex-direction:column; gap:12px;">
    <div style="font-size:46px; font-weight:200; color:{INK};">Откуда цвет</div>
    <div class="note">Каждое значение прочитано из com.android.systemui.apk этой машины через
    aapt2 — по имени ресурса справа. BYD держит несколько скинов в одном SystemUI; это варианты
    _denza, отличающиеся тем, что акцент здесь бледная шампань, а не циан.</div>
  </div>

  <div style="display:flex; flex-direction:column; gap:18px;">{rows}</div>

  <div class="note">Имена ресурсов стоят на своей строке во всю ширину, а не в колонке сетки:
  подчёркивание не даёт переноса, и qs_adjust_seekbar_text_color_denza занимал 245 пикселей в
  колонке шириной 210 — молча наезжая на соседнюю.</div>

  <div class="note">Наш прежний мятный #73E0BD не встречается в прошивке нигде.</div>
</div>
</x-dc>
<script data-dc-script data-props='{{"$preview":{{"width":1180,"height":1520}}}}'>
class Component extends DCLogic {{ renderVals() {{ return {{}}; }} }}
</script>
</body>
</html>
'''


if __name__ == '__main__':
    open('Kit.dc.html', 'w').write(kit())
    open('StockPalette.dc.html', 'w').write(palette())
    print('Kit and StockPalette rebuilt')
