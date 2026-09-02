#!/usr/bin/env ruby
# frozen_string_literal: true

require "fileutils"
require "open3"
require "tmpdir"

ROOT = File.expand_path("..", __dir__)
SMOKE = File.join(ROOT, "scripts", "run-release-apk-runtime-smoke.sh")
SANITIZER = File.join(ROOT, "scripts", "prepare-ci-artifacts.rb")
FINGERPRINT = "A1" * 32

def assert(condition, message)
  raise "Fallo del test del smoke release: #{message}" unless condition
end

Dir.mktmpdir("facturastock-release-smoke-test-") do |temporary_dir|
  apk_path = File.join(temporary_dir, "release.apk")
  File.binwrite(apk_path, "synthetic signed apk fixture\n")
  calls_path = File.join(temporary_dir, "adb-calls.txt")
  state_path = File.join(temporary_dir, "adb-state.txt")

  adb_path = File.join(temporary_dir, "adb")
  File.write(adb_path, <<~'RUBY')
    #!/usr/bin/env ruby
    require "fileutils"

    args = ARGV.dup
    File.open(ENV.fetch("MOCK_ADB_CALLS"), "a") { |file| file.puts(args.join(" ")) }
    if args == ["devices"]
      puts "List of devices attached"
      puts "emulator-5554\tdevice product:sdk_gphone model:Pixel_API_35"
      exit 0
    end
    if args[0, 2] == ["-s", "emulator-5554"]
      args.shift(2)
    else
      warn "unexpected serial: #{args.inspect}"
      exit 2
    end
    case args
    when ["get-state"]
      puts "device"
    when ["shell", "getprop", "ro.kernel.qemu"]
      puts "1"
    when ["shell", "getprop", "ro.build.version.sdk"]
      puts "35"
    when ["shell", "getprop", "ro.product.model"]
      puts "Pixel_API_35"
    when ["logcat", "-b", "all", "-c"]
      # no-op
    when ["install", "-r", ENV.fetch("MOCK_APK")]
      puts "Performing Streamed Install"
      puts "Success"
    when ["shell", "dumpsys", "package", "com.facturastock.app"]
      puts "  versionCode=42 minSdk=26 targetSdk=36"
      puts "  versionName=2.4.0"
    when ["shell", "pm", "path", "com.facturastock.app"]
      puts "package:/data/app/synthetic/base.apk"
    when ["pull", "/data/app/synthetic/base.apk", args[2]]
      FileUtils.cp(ENV.fetch("MOCK_APK"), args[2])
      puts "1 file pulled"
    when ["shell", "am", "force-stop", "com.facturastock.app"]
      File.write(ENV.fetch("MOCK_ADB_STATE"), "stopped")
    when ["shell", "pidof", "com.facturastock.app"]
      puts "1234" if File.exist?(ENV.fetch("MOCK_ADB_STATE")) && File.read(ENV.fetch("MOCK_ADB_STATE")) == "running"
    when ["shell", "dumpsys", "activity", "activities"]
      puts "mResumedActivity: ActivityRecord{abc com.facturastock.app/.MainActivity}"
    when ["logcat", "-b", "events", "-d", "-v", "brief"]
      case ENV["MOCK_RUNTIME_HEALTH"]
      when "crash"
        puts "I/am_crash( 1000): [0,1234,com.facturastock.app,0,java.lang.IllegalStateException]"
      when "anr"
        puts "I/am_anr ( 1000): [0,1234,com.facturastock.app,0,Input dispatching timed out]"
      end
    when ["logcat", "-b", "crash", "-d", "-v", "threadtime"]
      # no-op; the event buffer is the canonical fixture.
    when ["logcat", "-d", "-v", "threadtime", "AndroidRuntime:E", "ActivityManager:E", "ActivityTaskManager:E", "*:S"]
      # no-op
    when ["shell", "dumpsys", "activity", "lastanr"]
      puts "No ANRs since boot"
    else
      if args[0, 4] == ["shell", "am", "start", "-W"]
        File.write(ENV.fetch("MOCK_ADB_STATE"), "running")
        puts "Starting: Intent"
        puts "Status: ok"
        puts "Activity: com.facturastock.app/com.facturastock.app.MainActivity"
        puts "Complete"
      else
        warn "unexpected adb call: #{args.inspect}"
        exit 2
      end
    end
  RUBY

  aapt_path = File.join(temporary_dir, "aapt")
  File.write(aapt_path, <<~'RUBY')
    #!/usr/bin/env ruby
    abort "unexpected aapt args" unless ARGV[0, 2] == ["dump", "badging"]
    version_code = ENV.fetch("MOCK_ARTIFACT_VERSION_CODE", "42")
    puts "package: name='com.facturastock.app' versionCode='#{version_code}' versionName='2.4.0' compileSdkVersion='36'"
    puts "launchable-activity: name='com.facturastock.app.MainActivity'  label='FacturaStock' icon=''"
  RUBY

  apksigner_path = File.join(temporary_dir, "apksigner")
  File.write(apksigner_path, <<~'RUBY')
    #!/usr/bin/env ruby
    fingerprint = ENV.fetch("MOCK_CERT_FINGERPRINT").downcase
    puts "Signer #1 certificate SHA-256 digest: #{fingerprint}"
  RUBY
  [adb_path, aapt_path, apksigner_path].each { |path| FileUtils.chmod(0o755, path) }

  base_environment = {
    "ADB_BIN" => adb_path,
    "AAPT_BIN" => aapt_path,
    "APKSIGNER_BIN" => apksigner_path,
    "MOCK_ADB_CALLS" => calls_path,
    "MOCK_ADB_STATE" => state_path,
    "MOCK_APK" => File.realpath(apk_path),
    "MOCK_CERT_FINGERPRINT" => FINGERPRINT,
    "FACTURASTOCK_VERSION_CODE" => "42",
    "FACTURASTOCK_VERSION_NAME" => "2.4.0",
    "FACTURASTOCK_EXPECTED_UPLOAD_CERT_SHA256" => FINGERPRINT,
    "FACTURASTOCK_SMOKE_REQUIRE_EMULATOR" => "true",
    "FACTURASTOCK_SMOKE_SETTLE_SECONDS" => "0",
  }

  success_evidence = File.join(temporary_dir, "success-evidence")
  stdout, stderr, status = Open3.capture3(
    base_environment,
    "bash",
    SMOKE,
    apk_path,
    success_evidence,
    chdir: ROOT,
  )
  install_diagnostic = File.exist?(File.join(success_evidence, "install.md")) ?
    File.read(File.join(success_evidence, "install.md")) : "sin install.md"
  assert(status.success?, "el caso sano falló: #{stdout}#{stderr}#{install_diagnostic}")
  assert(File.read(File.join(success_evidence, "result.csv")).include?(",PASS"), "falta PASS en evidencia")
  assert(File.exist?(File.join(success_evidence, "evidence.sha256")), "falta manifiesto de evidencia")
  calls = File.read(calls_path)
  assert(calls.scan("shell am start -W").length == 2, "MainActivity no se abrió dos veces")
  assert(calls.include?("shell am force-stop com.facturastock.app"), "falta force-stop")
  assert(calls.include?("pull /data/app/synthetic/base.apk"), "no se verificó el APK instalado")

  summaries_root = File.join(ROOT, "ci-summaries")
  artifacts_root = File.join(ROOT, "ci-artifacts")
  FileUtils.mkdir_p(summaries_root)
  FileUtils.mkdir_p(artifacts_root)
  staged_source = Dir.mktmpdir("release-smoke-contract-", summaries_root)
  staged_destination = Dir.mktmpdir("release-smoke-contract-", artifacts_root)
  begin
    FileUtils.cp_r(Dir.glob(File.join(success_evidence, "*")), staged_source)
    stdout, stderr, status = Open3.capture3(
      "ruby",
      SANITIZER,
      staged_destination,
      staged_source,
      chdir: ROOT,
    )
    assert(status.success?, "la evidencia no pasó el sanitizador: #{stdout}#{stderr}")
    relative_source = staged_source.delete_prefix("#{ROOT}/")
    assert(File.exist?(File.join(staged_destination, relative_source, "result.csv")),
           "el staging perdió el resultado cerrado")
    assert(File.exist?(File.join(staged_destination, relative_source, "runtime-health.csv")),
           "el staging perdió el resumen crash/ANR")
    assert(!File.exist?(File.join(staged_destination, relative_source, "runtime-events.log")),
           "el staging publicó logcat crudo")
  ensure
    FileUtils.remove_entry(staged_source) if File.exist?(staged_source)
    FileUtils.remove_entry(staged_destination) if File.exist?(staged_destination)
    Dir.rmdir(summaries_root) if Dir.exist?(summaries_root) && Dir.empty?(summaries_root)
    Dir.rmdir(artifacts_root) if Dir.exist?(artifacts_root) && Dir.empty?(artifacts_root)
  end

  FileUtils.rm_f(calls_path)
  FileUtils.rm_f(state_path)
  crash_evidence = File.join(temporary_dir, "crash-evidence")
  _stdout, stderr, status = Open3.capture3(
    base_environment.merge("MOCK_RUNTIME_HEALTH" => "crash"),
    "bash",
    SMOKE,
    apk_path,
    crash_evidence,
    chdir: ROOT,
  )
  assert(!status.success?, "el smoke aceptó un am_crash de FacturaStock")
  assert(stderr.include?("crash o ANR"), "el error de crash/ANR no es explícito")
  assert(File.read(File.join(crash_evidence, "result.csv")).include?(",FAIL"), "falta FAIL en evidencia")

  FileUtils.rm_f(calls_path)
  FileUtils.rm_f(state_path)
  anr_evidence = File.join(temporary_dir, "anr-evidence")
  _stdout, stderr, status = Open3.capture3(
    base_environment.merge("MOCK_RUNTIME_HEALTH" => "anr"),
    "bash",
    SMOKE,
    apk_path,
    anr_evidence,
    chdir: ROOT,
  )
  assert(!status.success?, "el smoke aceptó un am_anr de FacturaStock")
  assert(stderr.include?("crash o ANR"), "el rechazo de ANR no es explícito")

  FileUtils.rm_f(calls_path)
  FileUtils.rm_f(state_path)
  wrong_version_evidence = File.join(temporary_dir, "wrong-version-evidence")
  _stdout, stderr, status = Open3.capture3(
    base_environment.merge("MOCK_ARTIFACT_VERSION_CODE" => "41"),
    "bash",
    SMOKE,
    apk_path,
    wrong_version_evidence,
    chdir: ROOT,
  )
  assert(!status.success?, "el smoke aceptó otro versionCode")
  assert(stderr.include?("versionCode inesperado"), "el rechazo de versión no es explícito")
  calls = File.exist?(calls_path) ? File.read(calls_path) : ""
  assert(!calls.include?(" install "), "se instaló un APK cuya identidad ya era inválida")

  FileUtils.rm_f(calls_path)
  FileUtils.rm_f(state_path)
  wrong_certificate_evidence = File.join(temporary_dir, "wrong-certificate-evidence")
  _stdout, stderr, status = Open3.capture3(
    base_environment.merge("MOCK_CERT_FINGERPRINT" => "B2" * 32),
    "bash",
    SMOKE,
    apk_path,
    wrong_certificate_evidence,
    chdir: ROOT,
  )
  assert(!status.success?, "el smoke aceptó otro certificado de carga")
  assert(stderr.include?("huella del APK"), "el rechazo de certificado no es explícito")
  calls = File.exist?(calls_path) ? File.read(calls_path) : ""
  assert(!calls.include?(" install "), "se instaló un APK con certificado inválido")
end

puts "Release APK runtime smoke test: identidad, reinstalación y crash/ANR verificados con dobles."
