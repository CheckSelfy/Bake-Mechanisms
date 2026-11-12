# Повторный прогон #12 после слияния Stream B

Этот каталог — повторная приёмка #12 на **слитом** дереве (`fix/todo-streams` + Stream B из `feature/BM-34-gleb`).
Первый прогон ([2026-11-12-mob-acceptance](../2026-11-12-mob-acceptance)) сделан до слияния, поэтому не покрывает
объединённый код входа в стазис (флаг `FORCE`, пропуск одиночных тел, единый `StasisStabilizer`).

Команда: `python scripts/farm-lab.py mob-review --mode acceptance --server run/farm-lab-mob-acceptance-merged --evidence docs/testing/evidence/2026-11-12-mob-acceptance-merged`

## Итог — PASS

| Фаза | TPS | Median MSPT | p95 MSPT |
|---|---:|---:|---:|
| Нативные оригиналы | 8.521 | 115.603 | 129.86 |
| Все три фермы в `STASIS` | 20.001 | 0.267 | 2.864 |

Выработка за ровно 1200 игровых тиков совпала с профилем точно во всех 10 комбинациях ферма/предмет
(`actual == expected`). `forbidden-operator-commands: []`: ни `/tick freeze`, ни `/kill`, ни правка
persistence, ни высадка наездников не использовались.

## Две оговорки по экспорту

1. После `MOB_REVIEW_PASSED` сработал Paper watchdog: восстановленные **оригиналы** трёх ферм (8.5 TPS) не
   уложились в таймаут во время финального сохранения, стек — `RemoveBlockGoal.canUse` → `MoveToBlockGoal`
   (AI пиглинов). Это свойство измеряемой нагрузки, а не плагина: именно её стазис и убирает. Сервер
   завершился с кодом 70, поэтому `scripts/farm-lab.py` не успел скопировать артефакты, и файлы этого
   каталога скопированы вручную из `run/farm-lab-mob-acceptance-merged`. Вердикт прогона уже был записан.
2. Spark на Windows использует встроенный Java-движок (async-profiler недоступен для windows11/amd64),
   поэтому профили не сопоставимы построчно с Linux-прогонами.
