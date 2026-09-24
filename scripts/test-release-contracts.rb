#!/usr/bin/env ruby
# frozen_string_literal: true

require "open3"
require "rbconfig"
require "digest"
require "fileutils"
require "tmpdir"

ROOT = File.expand_path("..", __dir__)
PROFILE_CANDIDATES = File.join(ROOT, "scripts", "prepare-profile-candidates.rb")
PROFILE_CAPTURE = File.join(ROOT, "scripts", "run-local-profile-capture.rb")
PROFILE_TEST_CLASS = "com.facturastock.app.benchmark.FacturaStockBaselineProfile"

def assert(condition, message)
  raise "Fallo del contrato de release: #{message}" unless condition
end

def project_file(relative_path)
  File.read(File.join(ROOT, relative_path), encoding: "UTF-8")
end

workflow = project_file(".github/workflows/ci.yml")
# Sin nube ni publicación firmada: ninguno de esos jobs puede volver a la CI.
%w[signed-release firebase-emulator firebase-spark-emulator android-firebase-e2e].each do |removed_job|
  assert(!workflow.match?(/^  #{removed_job}:\s*$/), "la CI conserva el job retirado #{removed_job}")
end
assert(!workflow.match?(/cloud|firebase|spark/i), "la CI conserva referencias a la variante cloud")
%w[functions firebase.json firestore.rules app/src/cloud app/src/spark play].each do |removed_path|
  assert(!File.exist?(File.join(ROOT, removed_path)), "sobrevive el recurso de nube #{removed_path}")
end
external_actions = workflow.lines.grep(/^\s+-?\s*uses:\s+/)
assert(!external_actions.empty?, "el workflow no declara Actions externas")
external_actions.each do |line|
  reference = line[/uses:\s+([^\s#]+)/, 1]
  next if reference&.start_with?("./")

  assert(reference&.match?(/@[0-9a-f]{40}\z/),
         "Action mutable o SHA incompleto: #{reference.inspect}")
  assert(line.match?(/#\s+v\d/), "Action fijada sin comentario de versión: #{reference}")
end
gradle_properties = project_file("gradle.properties")
assert(gradle_properties.include?("android.r8.optimizedResourceShrinking=true"),
       "release no habilita el shrinker integrado de recursos de AGP 8.13")
version_catalog = project_file("gradle/libs.versions.toml")
app_build = project_file("app/build.gradle.kts")
assert(!app_build.match?(/create\("cloud"\)|create\("spark"\)|firebase\.crashlytics|cloudImplementation/),
       "el build de la app conserva la variante cloud, Spark o Firebase")
assert(!version_catalog.include?("com.google.firebase"), "el catálogo conserva dependencias Firebase")
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
assert(!version_catalog.include?("com.google.mlkit:barcode-scanning"),
       "el catálogo conserva el scanner de cámara ML Kit que ventas no usa")
assert(!app_build.include?("implementation(libs.mlkit.barcode.scanning)"),
       "la app conserva el scanner de cámara ML Kit que ventas no usa")
assert(app_build.include?("verifyLocalReleaseBundleOptimization"),
       "falta verificar perfiles/R8 en el AAB local")
assert(workflow.include?(":app:verifyLocalReleaseBundleOptimization"),
       "CI no inspecciona la optimización real del AAB local")
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
app_navigation = project_file("app/src/main/java/com/facturastock/app/navigation/FacturaStockApp.kt")
assert(app_navigation.include?("startDestination = AppRoutes.SALES"),
       "el benchmark de arranque espera Vender, pero cambió el destino después de onboarding")
scanner_input = project_file("app/src/main/java/com/facturastock/app/feature/common/ScannerCodeInput.kt")
assert(startup_journey.include?("private const val SALES_READY_TAG = \"scanner_code_reset\"") &&
       scanner_input.include?("const val RESET = \"scanner_code_reset\"") &&
       scanner_input.include?(".testTag(ScannerCodeInputTestTags.RESET)") &&
       startup_journey.include?("Until.findObject(By.res(SALES_READY_TAG).enabled(true))"),
       "el recorrido debe esperar la acción habilitada del lector de Vender")
assert(startup_journey.include?("Until.hasObject(By.res(SALES_READY_TAG))"),
       "el recorrido no espera el resource-id Compose que publica Vender")
assert(!startup_journey.include?("By.res(TARGET_PACKAGE, SALES_READY_TAG)"),
       "Compose publica el testTag sin namespace; el selector calificado nunca coincide")
assert(startup_journey.include?("fun prepare("),
       "falta preparar el onboarding antes de capturar perfiles o métricas")
assert(startup_journey.include?("am force-stop $TARGET_PACKAGE") &&
       startup_journey.include?("pidof $TARGET_PACKAGE"),
       "la corrida de preparación puede solapar mantenimiento con el arranque medido")
assert(benchmark_build.include?("create(\"local\")") && !benchmark_build.include?("create(\"cloud\")"),
       "el módulo benchmark debe exponer solo la variante local")
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
  :benchmark:assembleLocalProfile
].each do |task|
  assert(workflow.include?(task), "CI no compila el carril Profile: #{task}")
end
assert(profile_manifest.include?("<profileable android:shell=\"true\""),
       "profile no permite recopilación ART por shell")
assert(project_file("README.md").include?(":benchmark:connectedLocalProfileAndroidTest"),
       "falta documentar la generación fuente con localProfile")
assert(workflow.include?("connected${{ matrix.variant-prefix }}BenchmarkAndroidTest"),
       "CI no ejecuta Macrobenchmark sobre la variante benchmark aislada")
assert(workflow.include?(
         "android.testInstrumentationRunnerArguments.class=com.facturastock.app.benchmark.FacturaStockMacrobenchmark"
       ), "CI mezcla generación HRF con las métricas del APK minificado")
assert(project_file("README.md").include?(
         "com.facturastock.app.benchmark.FacturaStockMacrobenchmark"
       ), "los comandos documentados de medición no filtran la suite Macrobenchmark")
assert(workflow.include?("android-performance-${{ matrix.backend }}-${{ github.run_attempt }}"),
       "CI publica la evidencia de rendimiento sin identificar su variante")
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
       app_build.include?("^compileLocal(?:Debug|Release|Benchmark|Profile)Kotlin$"),
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

puts "Release contract test: perfiles, SDK 26/36 y retiro de la nube exigidos."
