# monorepo-microservices

POC Maven multi-module monorepo. Connects to Gate.io WebSocket and computes real-time market microstructure indicators (Glosten-Milgrom + OFI) using RxJava 3, Mutiny, Agrona, and FastUtil.

## Structure

```
monorepo-microservices/
├── market-domain/          # Immutable records: BookTicker, Trade, GlosenMilgromState, SpreadAnalysis
├── market-feed/            # Gate.io WebSocket (spot.book_ticker + spot.trades) via Agrona SPSC queue
├── market-analytics/       # GlosenMilgromAnalytics (FastUtil), OFIAnalytics (Mutiny)
├── market-app/             # Combined entry point: GM + OFI + throttleLast(1s)
└── market-runners/
    ├── analysis-runner/    # GlosenMilgromRunner, OFIRunner  → fat JAR
    └── book-ticker-runner/ # BookTickerRunner                → fat JAR
```

Dependency chain: `market-domain` ← `market-feed` ← `market-analytics` ← `market-app` / `*-runner`

## Build

```bash
# full reactor
mvn package -DskipTests -T 1C

# single module + transitive dependencies
mvn package -pl market-runners/analysis-runner --also-make -DskipTests
```

## Running

Every executable module produces a **fat JAR** (all dependencies bundled) during the `package` phase.

**Single main class** → `java -jar`:
```bash
java -jar market-runners/book-ticker-runner/target/book-ticker-runner-1.0-SNAPSHOT.jar ETH_USDT
java -jar market-app/target/market-app.jar
```

**Multiple main classes** → `java -cp` with explicit class:
```bash
java -cp market-runners/analysis-runner/target/analysis-runner-1.0-SNAPSHOT.jar \
     com.defrancesco.market.runners.GlosenMilgromRunner BTC_USDT

java -cp market-runners/analysis-runner/target/analysis-runner-1.0-SNAPSHOT.jar \
     com.defrancesco.market.runners.OFIRunner BTC_USDT 30
```

Or via Maven without a prior build:
```bash
mvn exec:java@gm  -pl market-runners/analysis-runner -Dexec.args="BTC_USDT"
mvn exec:java@ofi -pl market-runners/analysis-runner -Dexec.args="BTC_USDT 30"
```

## Adding a fat JAR to a new module

Add the following to the module's `pom.xml`:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-shade-plugin</artifactId>
    <executions>
        <execution>
            <id>shade</id>
            <phase>package</phase>
            <goals><goal>shade</goal></goals>
            <!-- for java -jar: add configuration with ManifestResourceTransformer -->
        </execution>
    </executions>
</plugin>
```

Version, `createDependencyReducedPom=false`, and `ServicesResourceTransformer` are inherited from the parent pom.

## Pipelines

### `compile` — automatic
Runs on every push/PR to `main`. Compiles and tests the full reactor.

| Optimisation | Detail |
|---|---|
| `-T 1C` | One Maven thread per CPU core; independent modules compile concurrently |
| Maven cache | `~/.m2/repository` cached on `pom.xml` hash with fallback restore-key |
| Java 25 Temurin | |

### `Build Module` — manual
Triggered from **Actions → Build Module → Run workflow**.

Select a module from the dropdown; fat JARs are uploaded as a downloadable artifact (7-day retention).

| Input | Behaviour |
|---|---|
| `market-runners` | Builds both runner sub-modules |
| `market-runners/analysis-runner` | analysis-runner + its dependencies only |
| any other module | The module + transitive dependencies (`--also-make`) |

Flags: `-T 1C -nsu -Dmaven.artifact.threads=8 -DskipTests`
