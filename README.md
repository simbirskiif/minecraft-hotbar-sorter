# Hotbar Sorter

Клиентский Fabric-мод для раскладки предметов по правилам в девяти слотах панели быстрого доступа. В репозитории настроен Stonecutter-набор версий от 1.21 до текущей стабильной 26.x: `1.21`, `1.21.1`–`1.21.11`, `26.1`–`26.1.2`, `26.2`, `26.3`. Код игры использует Mojang-имена; версии 26.x собираются без обфускации. Общая логика в `core` не импортирует Minecraft.

## Возможности

- Правила по ID предмета или тегу, приоритет, выбор первого/лучшего совпадения и фиксация слота.
- Настройка по K, сортировка отдельной переназначаемой клавишей.
- Конфигурация `config/hotbarsorter/config.json`; внешние изменения перечитываются во время игры. В JSON можно включить запуск при подборе, закрытии инвентаря, входе в мир, респавне или с интервалом.
- Свапы идут штатными игровыми вызовами по одному действию за тик, до 18 действий за запрос. Сортировка блокируется при постороннем экране или предмете на курсоре.
- Автосортировка запускается при изменении предметов в хотбаре или основном инвентаре (включено по умолчанию, включая покупки в магазинах после закрытия экрана). Перед применением правил мод объединяет совместимые неполные стаки штатным быстрым перемещением Minecraft.
- Полноценная система пресетов: быстрое переключение прямо на экране хотбара стрелками `‹` / `›`, индикация несохранённых правок `*`, предпросмотр 9 ячеек каждого пресета, сохранение, перезапись и удаление без правки JSON. Предустановлены стартовые шаблоны: *Выживание*, *Шахта*, *PvP*, *Строительство*.
- Обновлённый визуальный интерфейс: рамки слотов инвентаря с подсветкой, иконка фиксации слота (🔒), бейджи количества правил, визуальный фидбек при drag-and-drop (подсветка цели и фантомный предмет на курсоре), подробные всплывающие подсказки (тултипы).
- Расширенный каталог групп (мечи, кирки, топоры, лопаты, мотыги, луки, еда, шерсть, брёвна, доски, стрелы, зелья) и поиск предметов как по техническому ID, так и по локализованному названию на русском и английском языках.
- Русская и английская локализация.

`best` сравнивает распознанный уровень материала, оставшуюся прочность и количество. Для сторонних предметов без узнаваемого материала используется прочность и количество. Профили, Mod Menu-кнопка, импорт/экспорт и полный редактор категорий ещё не реализованы. До финальной сборки будет завершена совместимость с матрицей и проверка целевых версий.

## Сборка

Для запуска Gradle/Loom нужен JDK 25. Java toolchain для компиляции выбирается отдельно: JDK 21 для Minecraft 1.21.x и JDK 25 для 26.x; Foojay resolver скачает отсутствующий toolchain.

```powershell
.\gradlew.bat buildAllVersions
```

Команда собирает все Stonecutter-ноды и копирует отдельные JAR в `build/libs/`. Для core доступны тесты `:core:test`.

## Настройка

Откройте экран клавиш в Minecraft и назначьте **Sort hotbar now**. Нажмите K для настроек, выберите слот и нажмите **Выбрать предмет…** или **Выбрать группу…**: в списке предметов и групп показаны иконки. Можно также ввести ID (например, `minecraft:golden_apple`) или тег (например, `#minecraft:wool`) вручную. Для мечей используйте группу `#minecraft:swords`. Предметы без совпадений остаются на месте по умолчанию; `unmatched: "inventory"` отправляет их из незадействованных слотов панели в первые свободные слоты инвентаря. Слот с правилом `{ "lock": true }` не меняется.

Пример правила:

```json
{
  "configVersion": 1,
  "enabled": true,
  "trigger": { "onHotbarChange": true, "onInventoryChange": true, "onPickup": false, "onInventoryClose": false, "onWorldJoin": false, "onRespawn": false, "intervalTicks": 0, "hotkeyOnly": false },
  "disabledServers": ["play.example.net"],
  "slots": {
    "1": [{ "match": "#minecraft:swords", "priority": 10, "pick": "best" }],
    "2": [{ "match": "minecraft:golden_apple", "priority": 10 }],
    "3": [{ "match": "#minecraft:wool", "priority": 5 }],
    "9": [{ "lock": true }]
  },
  "unmatched": "keep"
}
```

## Совместимость

Используются обычные действия инвентаря. На серверах, запрещающих автоматизацию инвентаря, применение мода остаётся на усмотрение игрока. Конфликты с Inventory Profiles Next и Mouse Tweaks пока не проверены.

Поддержка версий будет считаться подтверждённой после финальной сборки и smoke-проверок; сейчас матрица подготовлена, но результаты этой проверки ещё не внесены.

## English

Hotbar Sorter is a client-side Fabric mod that arranges items in the nine hotbar slots using item ID or tag rules. Configure it with **K**, assign **Sort hotbar now** in Controls, and edit `config/hotbarsorter/config.json` directly when needed. Config changes reload while the game is running. Sorting uses normal inventory swap actions, one per tick, and pauses when a non-inventory screen is open, the cursor carries an item, or a manual rearrangement was just made.

Run Gradle/Loom with JDK 25. The Java toolchain resolver selects JDK 21 for Minecraft 1.21.x and JDK 25 for 26.x. Build all configured Minecraft nodes with `./gradlew buildAllVersions`. The matrix currently lists Minecraft 1.21 through 1.21.11 and 26.1 through 26.3. Automatic triggers and server exclusions can be configured in JSON. Profiles and Mod Menu integration are not implemented yet. Use the mod only on servers that allow inventory automation.
