# LLM-модуль: конфигурация клиента и пайплайн генерации сидов

## 1. Назначение модуля

Модуль `src/main/kotlin/llm/` представляет собой надстройку над фаззером, обеспечивающую взаимодействие с большими языковыми моделями. Функционально он разделён на два независимых направления: формирование начального пула сидов на основе известных performance-дефектов OpenJDK и генерация мутаций сидов на каждой итерации эволюционного цикла.

Модуль организован в четыре подпакета. `client/` содержит абстракцию LLM-провайдера и управление конфигурацией. `parsing/` реализует скрапер OpenJDK JIRA. `generate/` реализует пайплайн «баг → программа». `mutation/` содержит LLM-ориентированные мутационные стратегии.

---

## 2. Конфигурация клиентского слоя (`llm/client/`)

### 2.1 Модель конфигурации и приоритет параметров

Центральным объектом конфигурации является датакласс `ConfigModel` с полями `provider`, `baseUrl`, `apiKey`, `model`, `temperature`, `topP`, `maxTokens` и `timeoutSeconds`. Загрузка конфигурации осуществляется статическим методом `ConfigModel.load()`, который применяет трёхуровневый приоритет для чувствительных параметров (`provider`, `baseUrl`, `apiKey`, `model`):

```
Уровень 1: переменные окружения ОС — System.getenv()
                        используется в Docker и CI-окружениях

Уровень 2:              файл .env в рабочей директории
                        используется при локальной разработке

Уровень 3 (fallback):   значения из /resources/setting.json
                        содержит defaults
```

Параметры `temperature`, `topP`, `maxTokens` и `timeoutSeconds` считываются из `setting.json` и не переопределяются через переменные окружения.

Файл `.env` `loadDotEnv()`: строки-комментарии и пустые строки игнорируются, поддерживается формат `KEY=VALUE` с необязательными кавычками вокруг значения.

### 2.2 Абстракция провайдера и HTTP-клиент

Интерфейс `LlmClient` определяет два метода: `generate(userPrompt, systemPrompt)` для одно-shot запросов и `chat(messages)` для многоходового диалога. Провайдер выбирается через `enum LlmProvider { OPENAI, OLLAMA }`.

`OpenAiClient` отправляет `POST /chat/completions` на любой OpenAI-совместимый эндпоинт. Тело запроса содержит массив `messages: [{role, content}]`; ответ извлекается из `choices[0].message.content`. `OllamaClient` обращается к локальному серверу Ollama (`POST /api/generate`) и читает стримящийся ответ построчно, склеивая поле `response` каждого JSON-чанка до появления флага `done: true`.

Конфигурация `OkHttpClient` с таймаутами (`connectTimeout`, `readTimeout`, `writeTimeout`, `callTimeout`) вынесена в функцию-расширение `ConfigModel.buildHttpClient()`.

### 2.3 Фабрика и проверка соединения

`LlmClientFactory.create(config)` возвращает экземпляр `LlmClient` по значению `config.provider`. Регистрация дополнительных провайдеров производится через `LlmClientFactory.register`

---

## 3. Сбор performance-багов OpenJDK (`llm/parsing/`)

### 3.1 Параметры JQL-запроса

Сбор выполняется классом `OpenJdkPerformanceScraper`, который обращается к REST API `https://bugs.openjdk.org`. Поиск параметризован объектом `OpenJdkSearchConfig`: проект `JDK`, типы `Bug`/`Enhancement`, метки `Performance`, `performance`, `performance-prg`, `performance-test`, `PerformDynamicPatch`, сортировка `updated DESC`. Конкретный JQL-запрос формируется в `OpenJdkJqlBuilder.performanceIssues`.

### 3.2 Сетевой слой и DTO-цепочка

Сетевой слой представлен классом `OpenJdkJiraApi` с единственным методом `search(jql, startAt, maxResults, fields)`, выполняющим `GET rest/api/2/search` через OkHttp. Ответ десериализуется в цепочку DTO: `JiraSearchResponse` → `JiraIssue` → `JiraFields` → `JiraNamed`. Все DTO аннотированы `@JsonIgnoreProperties(ignoreUnknown = true)`, что предотвращает ошибки при наличии неизвестных полей в ответе JIRA.

Далее `OpenJdkIssueMapper` выполняет двухшаговое преобразование: `fromDto(JiraIssue)` сглаживает структуру и собирает URL тикета, а `toBugsStructure(OpenJdkIssue)` приводит результат к внутреннему плоскому формату `BugsStructure` с полями `source`, `id`, `status`, `text` (конкатенация summary и description), `systemNumber` (ключ вида `JDK-8252185`), `component`, `subcomponent`, `type`, `resolution`, `fixVersion`.

Пагинация инкапсулирована в `OpenJdkPerformanceScraper.paginate`: страницы по 50 записей до достижения `maxIssues`.

### 3.3 Кэширование

При флаге `--parse-bugs true` скачанные баги сохраняются в `data/openjdk_bugs/` через `SingleFilePerBugExporter`.

---

## 4. Двухшаговый пайплайн «баг → Java-программа» (`llm/generate/`)

При флаге `--generate-seed true` полученные `BugsStructure` передаются в `JavaGenerationPipeline`. На каждый баг пайплайн выполняет два последовательных LLM-вызова.

```
[BugsStructure]
      │
      ▼
[BugPromptAnalyzer]
      │
      ├─ feasible=false ──► SKIPPED (баг нереализуем в чистой Java)
      │
      │ condensedHint
      ▼
[JavaProgramGenerator]
      │
      │ GeneratedProgram(bugId, className, code, valid)
      ▼
[GeneratedProgramValidator]
      │
      ├─ valid ──► GeneratedFromBugs/$className.java
      │
      └─ invalid ──► GeneratedFromBugs/invalid/$className.java
```

### 4.1 Шаг 1: BugPromptAnalyzer

`BugPromptAnalyzer` получает описание бага вместе с метаданными (`component`, `type`, `resolution`, `fixVersion`) и текстом, обрезанным до `maxContextChars` символов. Модель возвращает строгий JSON вида `{ "feasible", "reason", "condensedHint" }`.

Значение `feasible: false` означает, что баг не воспроизводим средствами чистой Java; помечается статусом `SKIPPED` и не передаётся на следующий шаг. Поле `condensedHint` содержит сжатое до пяти предложений описание того, что должна делать соответствующая Java-программа.

Парсинг JSON-ответа использует эвристику`extractJson`, которая вырезает первый блок от `{` до последней `}`. Если разобрать ответ не удалось, баг автоматически считается нереализуемым.

### 4.2 Шаг 2: JavaProgramGenerator

`JavaProgramGenerator` получает `BugsStructure` и `condensedHint` из первого шага. Из поля `bug.systemNumber` детерминированно выводится валидное Java-имя класса через `toSafeClassName`.

Имя класса используется в промпте в явной инструкции «The public class MUST be named exactly: …», в блоке OUTPUT RULES и в шаблоне `public class $className { … }`.

Результатом шага является объект `GeneratedProgram(bugId, className, code, valid)`, где `valid` определяется локальным предикатом `isValidJava`, проверяющим наличие подстрок `class $className` и `public static void main` в сгенерированном коде.

### 4.3 Валидация, компиляция и сохранение на диск

`GeneratedProgramValidator` принимает `GeneratedProgram` и компилирует его в целевую директорию через `JavaSourceCompiler.compileToDir()`. `JavaSourceCompiler` централизует всю логику `javax.tools`: формирует `JavaFileObject` из исходного кода в памяти, запускает `ToolProvider.getSystemJavaCompiler()` с опцией `--release $javaVersion` и записывает `.class`-файл в указанную директорию.

Валидные программы записываются в `src/test/resources/GeneratedFromBugs/$className.java`, невалидные — в подкаталог `invalid/` той же директории. Совпадение имени файла с именем публичного класса обязательно. Сгенерированные программы доступны для загрузки как начальные сиды фаззера через аргумент `--seedsDir src/test/resources/GeneratedFromBugs`.

### 4.4 Итоговый отчёт

`JavaGenerationPipeline.processAndSave` перехватывает исключения сети и парсинга на уровне каждого бага, оборачивая исход в `PipelineResult` одного из четырёх видов: `SUCCESS`, `SKIPPED`, `ANALYSIS_ERROR`, `GENERATION_ERROR`. По завершении партии возвращается `BatchReport` с полями `total`, `skipped`, `generated`, `invalid`, `errors`.

