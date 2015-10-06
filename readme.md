jcoro
===

Build and run tests:

```bash
gradlew :jcoro-app:build --rerun-tasks
```

Build and run SyncaServer:

```bash
gradlew :jcoro-app:build
java -cp jcoro-api/build/libs/jcoro-api-1.0.jar:jcoro-app/build/classes/instrumented org.jcoro.SyncaServer
```

(in Windows change `:` symbol to `;` between classpaths).

After that you can check the server is alive using `curl`:

```bash
curl -i "http://localhost:8080"
```