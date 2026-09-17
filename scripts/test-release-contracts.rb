#!/usr/bin/env ruby
# frozen_string_literal: true

require "open3"
require "rbconfig"
require "digest"
require "fileutils"
require "tmpdir"

ROOT = File.expand_path("..", __dir__)
PREPARE_SIGNING = File.join(ROOT, "scripts", "prepare-release-signing.sh")
MANIFEST_POLICY = File.join(ROOT, "scripts", "verify-release-manifest-metadata.rb")
PROFILE_CANDIDATES = File.join(ROOT, "scripts", "prepare-profile-candidates.rb")
PROFILE_CAPTURE = File.join(ROOT, "scripts", "run-local-profile-capture.rb")
PROFILE_TEST_CLASS = "com.facturastock.app.benchmark.FacturaStockBaselineProfile"

def assert(condition, message)
  raise "Fallo del contrato de release: #{message}" unless condition
end

def project_file(relative_path)
  File.read(File.join(ROOT, relative_path), encoding: "UTF-8")
end

def workflow_step(workflow, name)
  marker = "      - name: #{name}\n"
  body = workflow.split(marker, 2)[1]
  assert(body, "falta el step #{name}")
  body.split(/^      - name: /, 2).first
end

def manifest_tree(crashlytics_value: "(type 0x12)0x0", omit_crashlytics: false)
  metadata = %w[
    firebase_analytics_collection_enabled
    firebase_crashlytics_collection_enabled
    google_analytics_default_allow_ad_personalization_signals
    google_analytics_adid_collection_enabled
    google_analytics_automatic_screen_reporting_enabled
  ]
  metadata.delete("firebase_crashlytics_collection_enabled") if omit_crashlytics

  metadata.map do |name|
    value = name == "firebase_crashlytics_collection_enabled" ? crashlytics_value : "(type 0x12)0x0"
    <<~TREE
      E: meta-data
        A: android:name(0x01010003)="#{name}" (Raw: "#{name}")
        A: android:value(0x01010024)=#{value}
    TREE
  end.join
end

workflow = project_file(".github/workflows/ci.yml")
signed_release = workflow.split(/^  signed-release:\s*$/, 2)[1]
assert(signed_release, "falta el job signed-release")
assert(workflow.include?("release_version_code:"), "falta el input manual release_version_code")
assert(workflow.include?("release_version_name:"), "falta el input manual release_version_name")
assert(!signed_release.include?("github.event_name == 'push'"),
       "signed-release no debe materializar secretos ni artefactos en cada push")
assert(!signed_release.include?("github.run_number"),
       "signed-release no debe derivar la versión del contador global del workflow")
assert(workflow.include?("github.event_name == 'workflow_dispatch' && github.run_id || 'continuous'"),
       "un push no debe cancelar un despacho manual de release")
assert(signed_release.scan("${{ inputs.release_version_code }}").length == 4,
       "forward-only, build, verificación y smoke deben compartir el versionCode manual")
assert(signed_release.scan("${{ inputs.release_version_name }}").length == 3,
       "build, verificación y smoke deben compartir el versionName manual")
external_actions = workflow.lines.grep(/^\s+-?\s*uses:\s+/)
assert(!external_actions.empty?, "el workflow no declara Actions externas")
external_actions.each do |line|
  reference = line[/uses:\s+([^\s#]+)/, 1]
  next if reference&.start_with?("./")

  assert(reference&.match?(/@[0-9a-f]{40}\z/),
         "Action mutable o SHA incompleto: #{reference.inspect}")
  assert(line.match?(/#\s+v\d/), "Action fijada sin comentario de versión: #{reference}")
end
bucket_binding =
  "FACTURASTOCK_FIREBASE_STORAGE_BUCKET: ${{ secrets.FACTURASTOCK_FIREBASE_STORAGE_BUCKET }}"
prepare_step = workflow_step(workflow, "Validate protected secrets and temporary keystore")
build_step = workflow_step(workflow, "Build signed cloud AAB and derive its universal APK")
forward_only_step = workflow_step(workflow, "Enforce monotonic version and forward-only Room schema")
runtime_smoke_step = workflow_step(workflow, "Install and reopen the exact AAB-derived APK")
assert(prepare_step.include?(bucket_binding), "el step de preparación no recibe el bucket")
assert(build_step.include?(bucket_binding), "el step de build no recibe el bucket")
assert(workflow.scan(bucket_binding).length == 2, "el bucket debe exponerse solo en prepare y build")
assert(forward_only_step.include?("FACTURASTOCK_MAX_DISTRIBUTED_VERSION_CODE") &&
       forward_only_step.include?("FACTURASTOCK_MAX_DISTRIBUTED_ROOM_SCHEMA") &&
       forward_only_step.include?("verify-release-forward-only.sh"),
       "signed-release no bloquea versionCode o Room regresivos")
assert(runtime_smoke_step.include?("reactivecircus/android-emulator-runner@a421e43855164a8197daf9d8d40fe71c6996bb0d") &&
       runtime_smoke_step.include?("run-release-apk-runtime-smoke.sh") &&
       runtime_smoke_step.include?("FACTURASTOCK_SMOKE_REQUIRE_EMULATOR: \"true\""),
       "signed-release no instala el APK derivado exacto en un emulador efímero")
assert(signed_release.index("Install and reopen the exact AAB-derived APK") <
       signed_release.index("Attest AAB and AAB-derived APK provenance"),
       "el artefacto se atesta antes de superar el smoke runtime")
assert(signed_release.include?("RUNTIME_SMOKE_OUTCOME") &&
       signed_release.include?("\"$RUNTIME_SMOKE_OUTCOME\" == success"),
       "un smoke fallido todavía puede materializar artefactos publicables")

android_firebase_job = workflow.split(/^  android-firebase-e2e:\s*$/, 2)[1]
assert(android_firebase_job, "falta el job Android↔Firebase")
android_firebase_job = android_firebase_job.split(/^  android-sdk-smoke:\s*$/, 2).first
assert(android_firebase_job.include?("npx firebase emulators:exec") &&
       android_firebase_job.include?("--only auth,firestore,functions,storage") &&
       android_firebase_job.include?("CloudPurchaseSagaE2ETest"),
       "la saga Android no corre dentro del Emulator Suite completo")
assert(workflow.scan("- android-firebase-e2e").length == 2,
       "empaquetado y firma no dependen explícitamente del E2E Android↔Firebase")

firebase_emulator_job = workflow.split(/^  firebase-emulator:\s*$/, 2)[1]
assert(firebase_emulator_job, "falta el job Firebase Emulator")
firebase_emulator_job = firebase_emulator_job.split(/^  room-migrations:\s*$/, 2).first
assert(firebase_emulator_job.include?("--test-concurrency=1"),
       "las suites que comparten Emulator Suite deben ejecutarse en serie")

spark_emulator_job = workflow.split(/^  firebase-spark-emulator:\s*$/, 2)[1]
assert(spark_emulator_job, "falta el gate separado de reglas Spark")
spark_emulator_job = spark_emulator_job.split(/^  room-migrations:\s*$/, 2).first
assert(spark_emulator_job.include?("--config ../firebase.spark.json") &&
       spark_emulator_job.include?("--project demo-facturastock-spark") &&
       spark_emulator_job.include?("FACTURASTOCK_SPARK_RULES_TEST=1") &&
       spark_emulator_job.include?("test/sparkSecurityRules.test.mjs"),
       "el gate Spark debe activar su suite y cargar sus propias reglas")
assert(workflow.scan("- firebase-spark-emulator").length == 2,
       "empaquetado y firma deben depender explícitamente del gate Spark")

prepare_source = project_file("scripts/prepare-release-signing.sh")
assert(prepare_source.include?("    FACTURASTOCK_FIREBASE_STORAGE_BUCKET\n"),
       "prepare-release-signing no exige el bucket")

Dir.mktmpdir("facturastock-release-contract-") do |temporary_dir|
  output_path = File.join(temporary_dir, "github-output")
  keystore_path = File.join(temporary_dir, "release.jks")
  environment = {
    "GITHUB_OUTPUT" => output_path,
    "FACTURASTOCK_SIGNING_KEYSTORE_BASE64" => "not-read-before-validation",
    "FACTURASTOCK_SIGNING_STORE_PASSWORD" => "synthetic-store-password",
    "FACTURASTOCK_SIGNING_KEY_ALIAS" => "synthetic-alias",
    "FACTURASTOCK_SIGNING_KEY_PASSWORD" => "synthetic-key-password",
    "FACTURASTOCK_FIREBASE_PROJECT_ID" => "synthetic-project",
    "FACTURASTOCK_FIREBASE_APPLICATION_ID" => "1:100:android:abcdef",
    "FACTURASTOCK_FIREBASE_API_KEY" => "AIza#{'A' * 35}",
    "FACTURASTOCK_FIREBASE_STORAGE_BUCKET" => nil,
    "FACTURASTOCK_PRIVACY_POLICY_URL" => "https://example.invalid/privacy",
  }
  _stdout, stderr, status = Open3.capture3(
    environment,
    "bash",
    PREPARE_SIGNING,
    keystore_path,
    chdir: ROOT,
  )
  assert(!status.success?, "prepare-release-signing aceptó un bucket ausente")
  assert(stderr.include?("FACTURASTOCK_FIREBASE_STORAGE_BUCKET"),
         "el error de preparación no identifica el bucket ausente")
  assert(!File.exist?(keystore_path), "se materializó el keystore antes de validar el contrato")
end

{
  "README.md" => 2,
  "docs/play/release-checklist.md" => 2,
}.each do |relative_path, minimum_occurrences|
  occurrences = project_file(relative_path).scan("FACTURASTOCK_FIREBASE_STORAGE_BUCKET").length
  assert(occurrences >= minimum_occurrences,
         "#{relative_path} no documenta completamente el bucket")
end
assert(project_file("docs/CLOUD_BACKUP_FIREBASE.md").include?("firebase.storageBucket"),
       "docs/CLOUD_BACKUP_FIREBASE.md no documenta el bucket")

verifier = project_file("scripts/verify-release-artifacts.sh")
assert(verifier.include?("verify-release-manifest-metadata.rb"),
       "verify-release-artifacts no delega la política de metadata")
assert(!verifier.include?("el artefacto no debe integrar configuración Crashlytics"),
       "sobrevive la política obsoleta que rechazaba Crashlytics")

gradle_properties = project_file("gradle.properties")
assert(gradle_properties.include?("android.r8.optimizedResourceShrinking=true"),
       "release no habilita el shrinker integrado de recursos de AGP 8.13")
version_catalog = project_file("gradle/libs.versions.toml")
app_build = project_file("app/build.gradle.kts")
spark_boundaries = app_build[/^val verifyCloudSparkBoundaries by tasks\.registering \{.*?^\}/m]
spark_configuration = app_build[/^val verifyCloudSparkConfiguration by tasks\.registering \{.*?^\}/m]
offline_boundaries = app_build[/^verifyOfflineFirstBoundaries\.configure \{.*?^\}/m]
spark_prebuild = app_build[
  /^tasks\.matching \{ task -> task\.name == "preCloudSparkBuild" \}\.configureEach \{.*?^\}/m
]
assert(spark_boundaries && spark_configuration && offline_boundaries && spark_prebuild,
       "faltan gates separados para seguridad Spark, configuración Spark y build offline")
%w[firebaseProjectId firebaseApplicationId firebaseApiKey].each do |configuration_value|
  assert(!spark_boundaries.include?(configuration_value),
         "el gate de código Spark exige credenciales y bloquea builds locales")
  assert(spark_configuration.include?("check(#{configuration_value}.isNotBlank())") &&
         spark_configuration.include?(".matches(#{configuration_value})"),
         "la configuración Spark no valida presencia y sintaxis de #{configuration_value}")
end
%w[.useEmulator( FirebaseFunctions.getInstance FirebaseStorage.getInstance 10.0.2.2 localhost demo-facturastock].each do |fragment|
  assert(spark_boundaries.include?(%Q("#{fragment}")),
         "el gate de código Spark perdió la conexión prohibida #{fragment}")
end
assert(offline_boundaries.include?("verifyCloudAppCheckBoundaries, verifyCloudSparkBoundaries") &&
       !offline_boundaries.include?("verifyCloudSparkConfiguration"),
       "offline debe conservar controles de seguridad sin exigir la configuración Spark")
assert(spark_prebuild.include?("dependsOn(verifyCloudSparkConfiguration)") &&
       spark_configuration.include?("dependsOn(verifyCloudSparkBoundaries)"),
       "preCloudSparkBuild debe validar configuración y conexiones antes de empaquetar")
startup_profile = project_file("app/src/main/baselineProfiles/startup-prof.txt")
startup_rules = startup_profile.lines.map(&:strip).reject { |line| line.empty? || line.start_with?("#") }
profile_generator = project_file(
  "benchmark/src/main/java/com/facturastock/app/benchmark/FacturaStockBaselineProfile.kt"
)
macrobenchmark = project_file(
  "benchmark/src/main/java/com/facturastock/app/benchmark/FacturaStockMacrobenchmark.kt"
)
benchmark_activity = project_file(
  "app/src/benchmark/java/com/facturastock/app/performance/PerformanceBenchmarkActivity.kt"
)
startup_journey = project_file(
  "benchmark/src/main/java/com/facturastock/app/benchmark/StartupJourney.kt"
)
startup_application = project_file(
  "app/src/main/java/com/facturastock/app/FacturaStockApplication.kt"
)
assert(!startup_application.include?("startupObservabilitySink"),
       "Application materializa el grafo de observabilidad/Firebase antes del primer frame")
main_activity = project_file("app/src/main/java/com/facturastock/app/MainActivity.kt")
benchmark_build = project_file("benchmark/build.gradle.kts")
profile_manifest = project_file("app/src/profile/AndroidManifest.xml")
cloud_manifest = project_file("app/src/cloud/AndroidManifest.xml")
assert(!version_catalog.include?("com.google.mlkit:barcode-scanning"),
       "el catálogo conserva el scanner de cámara ML Kit que ventas no usa")
assert(!app_build.include?("implementation(libs.mlkit.barcode.scanning)"),
       "la app conserva el scanner de cámara ML Kit que ventas no usa")
assert(app_build.include?("verifyLocalReleaseBundleOptimization"),
       "falta verificar perfiles/R8 en el AAB local")
assert(app_build.include?("verifyCloudReleaseBundleOptimization"),
       "falta verificar perfiles/R8 en el AAB cloud")
assert(workflow.include?(":app:verifyLocalReleaseBundleOptimization"),
       "CI no inspecciona la optimización real del AAB local")
assert(workflow.include?(":app:verifyCloudReleaseBundleOptimization"),
       "CI no inspecciona la optimización real del AAB cloud")
assert(!startup_rules.empty?, "el Startup Profile está vacío")
assert(startup_rules.all? { |rule| rule.match?(/\A[HSP]*Lcom\/facturastock\/app\//) },
       "el Startup Profile amplía el layout DEX a owners de dependencias")
assert(startup_rules.all? do |rule|
         if rule.include?("->")
           rule.split("L", 2).first.include?("S")
         else
           rule.match?(/\A[HSP]*Lcom\/facturastock\/app\/[^;]+;\z/)
         end
       end, "Startup solo admite métodos S o descriptores de clase de la app")
assert(profile_generator.include?("includeInStartupProfile = true"),
       "el generador no exporta el Startup Profile")
assert(profile_generator.include?("includeInStartupProfile = false"),
       "el generador no exporta por separado el Baseline Profile")
assert(profile_generator.include?("^[HSP]*Lcom/facturastock/app/"),
       "el generador no filtra por owner de la app")
assert(profile_generator.include?("filterPredicate = applicationProfileRule::matches") &&
       !profile_generator.include?("deferredMaintenanceOwnerPrefixes"),
       "el generador oculta contaminación mediante una blacklist de reglas")
assert(profile_generator.include?("StartupJourney.prepare(resetPersistentState = true)"),
       "el generador no limpia/prepara Vender fuera de la captura")
assert(startup_journey.include?("pm clear $TARGET_PACKAGE") &&
       startup_journey.include?("--ez $SUPPRESS_DEFERRED_STARTUP_EXTRA true"),
       "la preparación no aísla estado durable y trabajo posterior al primer contenido")
onboarding_screen = project_file("app/src/main/java/com/facturastock/app/feature/onboarding/OnboardingRoute.kt")
onboarding_tags = project_file("app/src/main/java/com/facturastock/app/feature/onboarding/OnboardingTestTags.kt")
onboarding_state = project_file("app/src/main/java/com/facturastock/app/feature/onboarding/OnboardingContract.kt")
journey_tag_values = startup_journey.scan(/private const val ([A-Z_]+_TAG) = "(onboarding_[^"]+)"/).to_h
onboarding_tag_names = onboarding_tags.scan(/const val ([A-Z_]+) = "([^"]+)"/).to_h.invert
startup_journey.scan(/enterText\(device,\s*([A-Z_]+),/).flatten.each do |input_tag|
  ui_tag = onboarding_tag_names[journey_tag_values[input_tag]]
  assert(ui_tag && onboarding_screen.include?(".testTag(OnboardingTestTags.#{ui_tag})"),
         "el harness intenta rellenar un campo de onboarding que ya no existe: #{input_tag}")
end
assert(startup_journey.include?("enterText(device, BUSINESS_NAME_TAG, BENCHMARK_BUSINESS_NAME)") &&
       startup_journey.include?("Until.findObject(By.res(SUBMIT_TAG).enabled(true))") &&
       startup_journey.include?("submit.click()"),
       "la preparación debe completar y enviar el onboarding mediante sus controles reales")
assert(onboarding_state.include?("val warehouseName: String = DEFAULT_WAREHOUSE_NAME") &&
       onboarding_state.match?(/const val DEFAULT_WAREHOUSE_NAME = "[^\"]+"/),
       "el onboarding compacto necesita conservar un almacén predeterminado válido")
assert(main_activity.include?("allowsDeferredStartupHarnessSuppression(BuildConfig.BUILD_TYPE)") &&
       startup_application.include?("buildType == \"benchmark\" || buildType == \"profile\"") &&
       startup_application.include?("!BuildConfig.RUN_DEFERRED_STARTUP"),
       "la supresión del harness puede alcanzar builds distribuibles")
assert(profile_generator.include?("StartupJourney.waitForSalesReady"),
       "el generador termina antes de que Vender publique contenido operativo")
assert(macrobenchmark.include?("StartupJourney.waitForSalesReady(device)"),
       "coldStartup no valida que Vender esté realmente listo")
assert(benchmark_activity.include?("onCardPresentationsReady = { cardPresentationsReady = true }"),
       "el fixture de lista no publica cuándo terminó de precalcular sus 100 tarjetas")
assert(macrobenchmark.include?("By.res(TAG_LIST_PRESENTATIONS_READY)"),
       "el benchmark de lista empieza antes de que estén listas sus presentaciones")
sales_tags = project_file("app/src/main/java/com/facturastock/app/feature/sales/SalesTestTags.kt")
sales_screen = project_file("app/src/main/java/com/facturastock/app/feature/sales/SalesScreen.kt")
app_navigation = project_file("app/src/main/java/com/facturastock/app/navigation/FacturaStockApp.kt")
assert(app_navigation.include?("startDestination = AppRoutes.SALES"),
       "el benchmark de arranque espera Vender, pero cambió el destino después de onboarding")
assert(startup_journey.include?("private const val SALES_READY_TAG = \"sales_entry_kind_screen\"") &&
       sales_tags.include?("const val ENTRY_KIND_SCREEN = \"sales_entry_kind_screen\"") &&
       sales_screen.include?(".testTag(SalesTestTags.ENTRY_KIND_SCREEN)"),
       "el recorrido debe esperar el selector real de ventas, no sólo su contenedor")
assert(startup_journey.include?("Until.hasObject(By.res(SALES_READY_TAG))"),
       "el recorrido no espera el resource-id Compose que publica Vender")
assert(startup_journey.include?("private const val SALES_CASH_ENTRY_TAG = \"sales_cash_entry\"") &&
       sales_tags.include?("const val CASH_ENTRY = \"sales_cash_entry\"") &&
       sales_screen.include?(".testTag(SalesTestTags.CASH_ENTRY)") &&
       startup_journey.include?("Until.findObject(By.res(SALES_CASH_ENTRY_TAG).enabled(true).clickable(true))"),
       "el recorrido termina antes de que la selección de venta al contado sea interactiva")
assert(!startup_journey.include?("By.res(TARGET_PACKAGE, SALES_READY_TAG)") &&
       !startup_journey.include?("By.res(TARGET_PACKAGE, SALES_CASH_ENTRY_TAG)"),
       "Compose publica el testTag sin namespace; el selector calificado nunca coincide")
assert(startup_journey.include?("fun prepare("),
       "falta preparar el onboarding antes de capturar perfiles o métricas")
assert(startup_journey.include?("am force-stop $TARGET_PACKAGE") &&
       startup_journey.include?("pidof $TARGET_PACKAGE"),
       "la corrida de preparación puede solapar mantenimiento con el arranque medido")
assert(benchmark_build.include?("create(\"local\")") && benchmark_build.include?("create(\"cloud\")"),
       "el módulo benchmark no expone variantes local/cloud separadas")
assert(benchmark_build.include?("create(\"profile\")"),
       "falta la variante instrumentadora dedicada a generar perfiles fuente")
assert(app_build.include?("create(\"profile\")") &&
       app_build.include?("!profile.isMinifyEnabled && !profile.isShrinkResources"),
       "profile no garantiza generación sin símbolos residuales de R8")
assert(app_build.include?("val verifyLocalProfileRuleResolution by tasks.registering") &&
       app_build.include?("variant.name == \"localProfile\"") &&
       app_build.include?("variant.artifacts.get(SingleArtifact.APK)") &&
       app_build.include?("variant.artifacts.getBuiltArtifactsLoader()") &&
       app_build.include?("dependsOn(\"assembleLocalProfile\")"),
       "el gate de reglas exactas no inspecciona el APK localProfile mediante la API de artefactos")
assert(app_build.include?("assets/dexopt/baseline.prof") &&
       app_build.include?("ArtProfile(input)") &&
       app_build.include?("dumpProfile(") &&
       app_build.include?("ObfuscationMap.Empty") &&
       app_build.include?("missingExactRules"),
       "el gate no resuelve las reglas exactas con Profgen contra DEX no minificado")
assert(app_build.include?("verifyLocalProfileRuleResolution,") &&
       app_build.include?("regenera la captura atómica"),
       "el gate release no bloquea perfiles exactos obsoletos")
assert(app_build.include?("val variantMangledProfileMethod") &&
       app_build.include?("métodos internal ligados a una variante"),
       "los perfiles fuente aceptan métodos internal no portables entre variantes")
assert(app_build.include?("buildConfigField(\"boolean\", \"RUN_DEFERRED_STARTUP\", \"true\")") &&
       app_build.include?("buildConfigField(\"boolean\", \"RUN_DEFERRED_STARTUP\", \"false\")"),
       "profile no aísla el mantenimiento posterior a Vender de la captura Startup")
%w[
  :app:assembleLocalProfile
  :app:assembleCloudProfile
  :benchmark:assembleLocalProfile
  :benchmark:assembleCloudProfile
].each do |task|
  assert(workflow.include?(task), "CI no compila el carril Profile: #{task}")
end
assert(profile_manifest.include?("<profileable android:shell=\"true\""),
       "profile no permite recopilación ART por shell")
assert(cloud_manifest.include?("com.google.firebase.provider.FirebaseInitProvider") &&
       cloud_manifest.include?("tools:node=\"remove\""),
       "cloud vuelve a inicializar Firebase mediante ContentProvider antes de Application")
assert(app_build.include?("com.google.firebase.provider.FirebaseInitProvider") &&
       app_build.include?("cloudRelease debe conservar la inicialización Firebase perezosa"),
       "el manifest release no bloquea la reintroducción de FirebaseInitProvider")
assert(project_file("README.md").include?(":benchmark:connectedLocalProfileAndroidTest"),
       "falta documentar la generación fuente con localProfile")
assert(workflow.include?("connected${{ matrix.variant-prefix }}BenchmarkAndroidTest"),
       "CI no ejecuta localBenchmark y cloudBenchmark mediante variantes aisladas")
assert(workflow.include?(
         "android.testInstrumentationRunnerArguments.class=com.facturastock.app.benchmark.FacturaStockMacrobenchmark"
       ), "CI mezcla generación HRF con las métricas del APK minificado")
assert(project_file("README.md").include?(
         "com.facturastock.app.benchmark.FacturaStockMacrobenchmark"
       ), "los comandos documentados de medición no filtran la suite Macrobenchmark")
assert(workflow.include?("android-performance-${{ matrix.backend }}-${{ github.run_attempt }}"),
       "CI puede mezclar la evidencia local y cloud")
assert(app_build.include?("val generateComposeCompilerReports by tasks.registering"),
       "falta la tarea opt-in de reportes Compose")
assert(app_build.include?("facturastock.composeCompilerReports"),
       "los reportes Compose no están protegidos por una propiedad opt-in")
assert(app_build.include?("incremental = false"),
       "los reportes Compose pueden quedar parciales por compilación incremental")
assert(!gradle_properties.include?("facturastock.composeCompilerReports=true"),
       "CI normal habilita reportes Compose de forma global")
assert(app_build.include?("startupDexStates.any { it }"),
       "el gate acepta AAB sin DEX de arranque")
assert(app_build.include?("Regex(\"^[HSP]*Lcom/facturastock/app/\")") &&
       !app_build.include?("maintainedRules.all { rule -> \"com/facturastock/app/\" in rule }"),
       "el gate de Baseline acepta owners externos que solo mencionan la app en una firma")
assert(app_build.include?("Toda regla Startup debe estar contenida también en el Baseline Profile"),
       "el gate no exige que Startup sea subconjunto del Baseline Profile")
profile_candidates_script = File.read(PROFILE_CANDIDATES)
profile_capture_script = File.read(PROFILE_CAPTURE)
assert(app_build.include?("El Baseline Profile perdió CUJ manuales de normalización") &&
       app_build.include?("El Startup Profile termina antes de Vender listo") &&
       app_build.include?("startupRuleOwner(rule) == requiredSalesOwner") &&
       profile_candidates_script.include?("profile_owner(rule) == owner"),
       "los gates no preservan CUJ manuales o evidencia de Vender operativo")
assert(profile_candidates_script.include?("PROFILE_RUNS_ROOT") &&
       profile_candidates_script.include?("run-manifest.txt") &&
       profile_candidates_script.include?("junit_sha256"),
       "el materializador no exige un run-id localProfile atómico y verificable")
assert(profile_capture_script.include?(":benchmark:connectedLocalProfileAndroidTest") &&
       profile_capture_script.include?("File.rename(temporary_run_dir, final_run_dir)") &&
       profile_capture_script.include?("expected_results"),
       "el wrapper no vincula JUnit 2/2 con un snapshot atómico")
assert(profile_capture_script.include?('ENV.fetch("ANDROID_SERIAL", "")') &&
       profile_capture_script.include?('Open3.capture3(adb, "-s", target_serial, "get-state")') &&
       profile_capture_script.include?('"shell", "getprop", "ro.kernel.qemu"') &&
       profile_capture_script.include?('system({ "ANDROID_SERIAL" => target_serial }, *gradle_command'),
       "la captura no exige y verifica un único emulador explícito antes de borrar datos")
Dir.mktmpdir("facturastock-profile-target-") do |temporary_dir|
  [nil, "", "physical-device", "emulator-5556,emulator-5554"].each do |invalid_serial|
    _stdout, stderr, status = Open3.capture3(
      { "ANDROID_SERIAL" => invalid_serial, "ADB" => File.join(temporary_dir, "missing-adb") },
      RbConfig.ruby,
      PROFILE_CAPTURE,
      "contract-target-rejection-#{Process.pid}",
      chdir: ROOT,
    )
    assert(!status.success? && stderr.include?("set ANDROID_SERIAL to one explicit disposable emulator"),
           "la captura aceptó un dispositivo ambiguo o físico antes de invocar adb/Gradle")
  end
end
assert(app_build.include?("compilerOptions.moduleName.set(\"facturastock_app\")") &&
       (app_build.include?("^compile(?:Local|Cloud)(?:Debug|Release|Benchmark|Profile)Kotlin$") ||
        app_build.include?("^compile(?:Local|Cloud)(?:Debug|Spark|Release|Benchmark|Profile)Kotlin$")),
       "las tareas Kotlin de app no fijan un moduleName portable entre variantes")

Dir.mktmpdir("facturastock-profile-candidate-") do |temporary_dir|
  profile_runs_root = File.join(ROOT, "app", "build", "reports", "profile-runs")
  FileUtils.mkdir_p(profile_runs_root)
  generated_dir = Dir.mktmpdir("contract-run-", profile_runs_root)
  other_run_dir = nil
  begin
  generated_startup = File.join(generated_dir, "startup-prof.txt")
  generated_baseline = File.join(generated_dir, "baseline-prof.txt")
  generated_junit = File.join(generated_dir, "junit.xml")
  maintained_startup = File.join(temporary_dir, "maintained-startup-prof.txt")
  maintained_baseline = File.join(temporary_dir, "maintained-baseline-prof.txt")
  candidate_dir = File.join(temporary_dir, "candidates")
  startup_method =
    "SPLcom/facturastock/app/MainActivity;->onCreate(Landroid/os/Bundle;)V"
  startup_class = "Lcom/facturastock/app/MainActivity;"
  sales_rules = [
    "Lcom/facturastock/app/feature/sales/SalesRouteKt;",
    "Lcom/facturastock/app/feature/sales/SalesScreenKt;",
  ]
  manual_cuj_rules = [
    "HPLcom/facturastock/app/domain/normalization/**->**(**)**",
    "Lcom/facturastock/app/domain/normalization/**;",
    "HPLcom/facturastock/app/feature/linereview/InvoiceLineReviewScreenKt**->**(**)**",
    "HPLcom/facturastock/app/feature/linereview/InvoiceLineReviewContract**->**(**)**",
    "HPLcom/facturastock/app/ui/format/FormattersKt**->**(**)**",
  ]
  generated_startup_rules = [startup_class, startup_method, *sales_rules]
  File.write(generated_startup, generated_startup_rules.join("\n") + "\n")
  File.write(generated_baseline, "#{startup_method}\n")
  junit_xml = <<~XML
    <testsuite name="#{PROFILE_TEST_CLASS}" tests="2" failures="0" errors="0" skipped="0">
      <testcase classname="#{PROFILE_TEST_CLASS}" name="coldStartup" />
      <testcase classname="#{PROFILE_TEST_CLASS}" name="baselineColdStartup" />
    </testsuite>
  XML
  File.write(generated_junit, junit_xml)
  write_run_manifest = lambda do
    manifest = [
      "format=1",
      "run_id=#{File.basename(generated_dir)}",
      "variant=localProfile",
      "test_class=com.facturastock.app.benchmark.FacturaStockBaselineProfile",
      "tests=2",
      "failures=0",
      "errors=0",
      "skipped=0",
      "startup_sha256=#{Digest::SHA256.file(generated_startup).hexdigest}",
      "baseline_sha256=#{Digest::SHA256.file(generated_baseline).hexdigest}",
      "junit_sha256=#{Digest::SHA256.file(generated_junit).hexdigest}",
      "",
    ].join("\n")
    File.write(File.join(generated_dir, "run-manifest.txt"), manifest)
  end
  write_run_manifest.call
  File.write(maintained_startup, "#{startup_method}\n")
  File.write(maintained_baseline, ([startup_method] + manual_cuj_rules).join("\n") + "\n")

  stdout, stderr, status = Open3.capture3(
    RbConfig.ruby,
    PROFILE_CANDIDATES,
    generated_startup,
    generated_baseline,
    maintained_startup,
    maintained_baseline,
    candidate_dir,
    chdir: ROOT,
  )
  assert(status.success?, "el merge seguro de perfiles falló: #{stdout}#{stderr}")
  baseline_candidate = File.read(File.join(candidate_dir, "baseline-prof.txt"))
  assert(baseline_candidate.include?(startup_class),
         "el candidato Baseline perdió un descriptor Startup")
  manual_cuj_rules.each do |manual_cuj_rule|
    assert(baseline_candidate.include?(manual_cuj_rule),
           "el candidato Baseline perdió el CUJ manual #{manual_cuj_rule}")
  end

  File.write(
    generated_startup,
    [
      startup_class,
      startup_method,
      "Lcom/facturastock/app/feature/home/HomeRouteKt;",
      "Lcom/facturastock/app/feature/home/HomeScreenKt;",
      "Lcom/facturastock/app/feature/home/HomeDashboardContentKt;",
    ].join("\n") + "\n",
  )
  write_run_manifest.call
  _stdout, stderr, status = Open3.capture3(
    RbConfig.ruby,
    PROFILE_CANDIDATES,
    generated_startup,
    generated_baseline,
    maintained_startup,
    maintained_baseline,
    candidate_dir,
    chdir: ROOT,
  )
  assert(!status.success? && stderr.include?("startup did not reach ready Sales owners"),
         "el materializador aceptó una captura Home obsoleta para el arranque en Vender")

  File.write(
    generated_startup,
    (sales_rules + [startup_class, "SPLcom/facturastock/app/MainActivity;->a(Laa/b;)V"]).join("\n") + "\n",
  )
  write_run_manifest.call
  _stdout, stderr, status = Open3.capture3(
    RbConfig.ruby,
    PROFILE_CANDIDATES,
    generated_startup,
    generated_baseline,
    maintained_startup,
    maintained_baseline,
    candidate_dir,
    chdir: ROOT,
  )
  assert(!status.success?, "el merge aceptó símbolos residuales de R8")
  assert(stderr.include?("appears obfuscated"),
         "el rechazo del HRF obfuscado no explica la causa")

  File.write(
    generated_startup,
    (generated_startup_rules + [
      "SPLcom/facturastock/app/MainActivity;->internal$facturastock_app_localProfile()V",
    ]).join("\n") + "\n",
  )
  write_run_manifest.call
  _stdout, stderr, status = Open3.capture3(
    RbConfig.ruby,
    PROFILE_CANDIDATES,
    generated_startup,
    generated_baseline,
    maintained_startup,
    maintained_baseline,
    candidate_dir,
    chdir: ROOT,
  )
  assert(!status.success?, "el merge aceptó mangling interno dependiente de la variante")
  assert(stderr.include?("variant-mangled"),
         "el rechazo del mangling dependiente de variante no explica la causa")

  other_run_dir = Dir.mktmpdir("other-run-", profile_runs_root)
  other_run_baseline = File.join(other_run_dir, "baseline-prof.txt")
  FileUtils.cp(generated_baseline, other_run_baseline)
  _stdout, stderr, status = Open3.capture3(
    RbConfig.ruby,
    PROFILE_CANDIDATES,
    generated_startup,
    other_run_baseline,
    maintained_startup,
    maintained_baseline,
    candidate_dir,
    chdir: ROOT,
  )
  assert(!status.success?, "el merge mezcló perfiles de corridas distintas")
  assert(stderr.include?("canonical pair from one atomic localProfile run id"),
         "el rechazo de corridas mezcladas no explica la causa")
  ensure
    FileUtils.remove_entry(generated_dir) if File.exist?(generated_dir)
    FileUtils.remove_entry(other_run_dir) if other_run_dir && File.exist?(other_run_dir)
  end
end

assert(workflow.include?("api-level: 26"), "CI no prueba el minSdk 26")
assert(workflow.include?("api-level: 36"), "CI no prueba el targetSdk 36")
assert(workflow.include?("DurablePrivateFilePublicationTest"),
       "el smoke minSdk no ejecuta la regresión de publicación durable")
assert(workflow.include?("HiltUdfRuntimeTest#mainActivityProtectsSensitiveScreensFromScreenshots"),
       "el smoke targetSdk no arranca MainActivity mediante Hilt")
assert(app_build.include?("val verifyCloudProductionReleaseQuality by tasks.registering"),
       "falta el gate de calidad de la variante productiva")
assert(app_build.include?("\"lintCloudRelease\""),
       "el candidato productivo no depende de Lint cloudRelease")
assert(app_build.include?("\"testCloudReleaseUnitTest\""),
       "el candidato productivo no depende de pruebas unitarias cloudRelease")
assert(app_build.include?("verifyCloudProductionReleaseQuality,"),
       "packageCloudProductionRelease no incorpora su gate de calidad")

stdout, stderr, status = Open3.capture3(
  RbConfig.ruby,
  MANIFEST_POLICY,
  stdin_data: manifest_tree,
  chdir: ROOT,
)
assert(status.success?, "la política rechazó Crashlytics=false: #{stdout}#{stderr}")

_stdout, stderr, status = Open3.capture3(
  RbConfig.ruby,
  MANIFEST_POLICY,
  stdin_data: manifest_tree(crashlytics_value: "(type 0x12)0xffffffff"),
  chdir: ROOT,
)
assert(!status.success?, "la política aceptó Crashlytics=true")
assert(stderr.include?("firebase_crashlytics_collection_enabled debe iniciar en false"),
       "el rechazo de Crashlytics=true no es explícito")

_stdout, stderr, status = Open3.capture3(
  RbConfig.ruby,
  MANIFEST_POLICY,
  stdin_data: manifest_tree(omit_crashlytics: true),
  chdir: ROOT,
)
assert(!status.success?, "la política aceptó un artefacto sin metadata Crashlytics")
assert(stderr.include?("firebase_crashlytics_collection_enabled debe aparecer exactamente una vez"),
       "el rechazo de metadata ausente no es explícito")

puts "Release contract test: perfiles, SDK 26/36, calidad productiva y privacidad exigidos."
