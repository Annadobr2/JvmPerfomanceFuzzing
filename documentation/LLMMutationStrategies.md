# LLM-модуль: мутационные стратегии

## 1. Место в эволюционном цикле

После того как начальный пул сидов сформирован (через пайплайн генерации, описанный в `LLMModule.md`, или из директории `--seedsDir`), на каждой итерации `EvolutionaryFuzzer` выбирает один сид и передаёт его исходный код в `AdaptiveMutator.mutate(javaCode, className, packageName)`. В LLM-ветви мутатор обращается к одной из стратегии из `MutationStrategyLLM`.

Поток данных в рамках одной итерации выглядит следующим образом:

```
[Seed.javaCode]
      │
      ▼
[AdaptiveMutator.mutate()]
      │
      │ выбор стратегии (рулетка по эффективности)
      ▼
[MutationStrategyLLM.apply(javaCode, className, packageName)]
      │
      ├─ PromptConstructor.build()  ──► промпт (role + instruction + context + rules)
      │
      ├─ LlmClient.generate()       ──► сырой ответ модели
      │
      ├─ stripFences()              ──► очистка от markdown-обёрток
      │
      └─ isValidJava()
            │
            ├─ valid   ──► JavaSourceCompiler.compileToBytes() ──► bytecode
            │
            └─ invalid ──► MutationResult(code, hadError=true)
```

---

## 2. Базовая архитектура (`llm/mutation/MutationStrategyLLM`)

### 2.1 Шаблонный метод apply

`MutationStrategyLLM` — абстрактный базовый класс, реализующий шаблонный метод `apply`. Метод последовательно выполняет: сборку промпта через `PromptConstructor`, LLM-вызов, очистку ответа от markdown-fences эвристикой `stripFences`, валидацию через `isValidJava` (проверка наличия `class $className` и `public static void main`), компиляцию и формирование `MutationResult(code, hadError)`.

Весь boilerplate промпта, касающийся имени пакета, имени публичного класса, запрета вложенных классов и ограничения по времени выполнения, сосредоточен в защищённом методе `outputRulesPrompt(className, packageName)`. Конкретные стратегии переопределяют только абстрактный метод `instructionPrompt(javaCode, className)`, возвращающий текст задачи для модели.

### 2.2 Конструирование промптов

`PromptConstructor` собирает финальный промпт из четырёх именованных блоков: `role` (системная роль), `instruction` (конкретная задача стратегии), `programContext` (исходный код мутируемой программы) и `outputRules` (жёсткие ограничения вывода). Если исходный код превышает `maxContextChars` символов, он обрезается с логированием факта усечения.

### 2.3 Компиляция мутированного кода

Компиляция исходника в байткод делегируется `JavaSourceCompiler.compileToBytes(className, code)`. Компилятор формирует `JavaFileObject` в памяти, запускает `ToolProvider.getSystemJavaCompiler()` с опцией `--release $javaVersion` и возвращает байткод запрошенного класса или `null` при ошибке компиляции. Отдельный `ByteArrayOutputStream` на каждый class-файл корректно обрабатывает программы, содержащие лямбды или анонимные внутренние классы. `JavaSourceCompiler` используется совместно пайплайном генерации и мутационными стратегиями, исключая дублирование логики `javax.tools`.

---

## 3. Статические стратегии

Статические стратегии присутствуют всегда, независимо от наличия динамически сгенерированных промптов.

### 3.1 ErasureStrategy

`ErasureStrategy` инструктирует модель переписать программу так, чтобы она сохраняла смысловую нагрузку (нагрузку на GC, JIT-пути и т.п.), но с принципиально другой структурой кода: без использования классов и методов оригинала. Цель стратегии — диверсификация популяции сидов за счёт структурных изменений при сохранении класса нагрузки.

### 3.2 RewriteCodeStrategy

`RewriteCodeStrategy` требует от модели сохранить логику программы, но переписать её с максимально отличающимся синтаксическим оформлением: другие имена переменных, иная декомпозиция на методы, альтернативные идиомы Java. Стратегия ориентирована на создание семантически эквивалентных вариантов, которые могут раскрывать различия в JIT-компиляции.

---

## 4. Динамические стратегии

### 4.1 MutationPromptExtractor

`MutationPromptExtractor` генерирует специализированные промпты мутаций на основе собранных `BugsStructure`. При флаге `--generate-strategy true` он отправляет баги в LLM и сохраняет полученные `MutationPrompt`-объекты в файл `data/mutation_prompts.json`. При `--generate-strategy false` (поведение по умолчанию) промпты загружаются из кэша. Если кэш пуст или файл отсутствует, используются только статические стратегии.

Каждый `MutationPrompt` содержит сформулированную LLM специфическую инструкцию мутации, ориентированную на паттерн производительности конкретного класса багов.

### 4.2 DynamicMutationStrategyLLM

`DynamicMutationStrategyLLM` — конкретная реализация `MutationStrategyLLM`, параметризованная объектом `MutationPrompt`. В `instructionPrompt` она подставляет текст промпта, сгенерированного на основе реального бага OpenJDK. 

---

## 5. Интеграция с AdaptiveMutator

В `Main.kt` финальный набор LLM-стратегий собирается следующим образом:

```kotlin
// Статические стратегии — присутствуют всегда
val staticLlmStrategies: List<MutationStrategyLLM> = listOf(
    RewriteCodeStrategy(promptConstructor, llmClient),
    ErasureStrategy(promptConstructor, llmClient),
)

// Динамические стратегии добавляются поверх статических
val newSelectedStrategies: List<MutationStrategyLLM> = if (mutationPrompts.isNotEmpty()) {
    val dynamic = mutationPrompts.map { prompt ->
        DynamicMutationStrategyLLM(prompt, promptConstructor, llmClient)
    }
    staticLlmStrategies + dynamic
} else {
    staticLlmStrategies
}

val mutator = AdaptiveMutator(jimpleTranslator, selectedStrategies, newSelectedStrategies)
```

`AdaptiveMutator` ведёт статистику эффективности каждой стратегии и применяет метод рулетки для взвешенного выбора следующей. Байткод-стратегии (`selectedStrategies`) и LLM-стратегии (`newSelectedStrategies`) управляются раздельно, что позволяет использовать их в различных комбинациях через аргумент командной строки `--mutation-strategies`.

---

## 6. Расширение модуля

Для добавления новой статической LLM-стратегии необходимо отнаследоваться от `MutationStrategyLLM` и переопределить метод `instructionPrompt`. Добавление значения в перечисление `MutationStrategyTypeLLM` и соответствующей ветки в `mutationStrategyFromEnum` делает стратегию доступной через CLI-аргумент `--mutation-strategies`.
