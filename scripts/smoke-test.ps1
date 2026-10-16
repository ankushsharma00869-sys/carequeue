<#
CareQueue end-to-end smoke test against the isolated Docker stack (backend 8081, frontend 3001).
It refuses to run unless the stack named in .env (CQ_STACK) is running, so it cannot test another project.

Runs the production-profile workflow with real HTTP calls:
  admin login -> create doctor (with schedule) and receptionist -> patient signup + login
  -> book -> pre-check-in call-next refused -> check-in -> call-next -> start -> complete
  -> patient sees COMPLETED -> analytics reflect it -> invalid JWT rejected.

Run on a machine whose clock is in the hospital timezone (Asia/Kolkata), between 00:00 and 23:30.
Passwords and tokens are never printed or written to disk.

Usage:
  powershell -ExecutionPolicy Bypass -File .\scripts\smoke-test.ps1
  (after a backend restart) powershell -ExecutionPolicy Bypass -File .\scripts\smoke-test.ps1 -VerifyOnly
#>
param(
    [string]$BaseUrl,
    [string]$FrontendUrl,
    [switch]$VerifyOnly
)

$ErrorActionPreference = "Stop"
$stateFile = Join-Path $PSScriptRoot "smoke-state.json"
$script:failures = 0

# Stack identity: test only the containers of the stack named in .env.
$envFile = Join-Path (Split-Path -Parent $PSScriptRoot) ".env"
if (-not (Test-Path $envFile)) { throw ".env not found. Run scripts/new-env.ps1 first." }
$stack = ((Get-Content $envFile) | Where-Object { $_ -match '^CQ_STACK=' }) -replace '^CQ_STACK=', ''
function EnvValue([string]$Key) {
    return ((Get-Content $envFile) | Where-Object { $_ -match "^$Key=" }) -replace "^$Key=", ""
}

$backendPort = EnvValue "BACKEND_HOST_PORT"
$frontendPort = EnvValue "FRONTEND_HOST_PORT"
if (-not $BaseUrl) { $BaseUrl = "http://localhost:$backendPort" }
if (-not $FrontendUrl) { $FrontendUrl = "http://localhost:$frontendPort" }

$running = docker ps --filter "name=^$stack-backend$" --format "{{.Names}}"
if ($running -ne "$stack-backend") { throw "Backend container '$stack-backend' is not running. Start this stack first." }

# The ports we are about to test must be published by THIS stack's containers, not by another project.
$publishedBackend = (docker port "$stack-backend" 8080) -join " "
$publishedFrontend = (docker port "$stack-frontend" 80) -join " "
if ($publishedBackend -notmatch ":$backendPort\b") { throw "'$stack-backend' does not publish port $backendPort (got: $publishedBackend)" }
if ($publishedFrontend -notmatch ":$frontendPort\b") { throw "'$stack-frontend' does not publish port $frontendPort (got: $publishedFrontend)" }

Write-Host "Testing stack '$stack' at $BaseUrl (frontend $FrontendUrl)" -ForegroundColor Cyan

function Call {
    param([string]$Method, [string]$Path, [string]$Token, $Body)
    $headers = @{}
    if ($Token) { $headers["Authorization"] = "Bearer $Token" }
    $request = @{
        Method          = $Method
        Uri             = "$BaseUrl$Path"
        Headers         = $headers
        UseBasicParsing = $true
        ContentType     = "application/json"
    }
    if ($null -ne $Body) { $request.Body = ($Body | ConvertTo-Json -Depth 8) }
    try {
        $response = Invoke-WebRequest @request
        $json = $null
        if ($response.Content) { $json = $response.Content | ConvertFrom-Json }
        return [pscustomobject]@{ Status = [int]$response.StatusCode; Json = $json }
    } catch {
        $code = 0
        if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
        return [pscustomobject]@{ Status = $code; Json = $null }
    }
}

function Check([string]$Name, [bool]$Ok) {
    if ($Ok) {
        Write-Host "PASS  $Name" -ForegroundColor Green
    } else {
        Write-Host "FAIL  $Name" -ForegroundColor Red
        $script:failures++
    }
}

function Login([string]$Email, [string]$Password) {
    $r = Call "POST" "/api/auth/login" $null @{ email = $Email; password = $Password }
    if ($r.Status -ne 200) { throw "login failed for $Email (HTTP $($r.Status))" }
    return $r.Json
}

function Next-BookableSlot {
    # A slot on the 15-minute grid, at least 10 minutes from now, on the host's clock.
    $base = (Get-Date).AddMinutes(10)
    $hour = [datetime]::new($base.Year, $base.Month, $base.Day, $base.Hour, 0, 0)
    $slot = $hour.AddMinutes([math]::Ceiling($base.Minute / 15) * 15)
    return $slot
}

# ---------------------------------------------------------------- verify-only (after restart)

if ($VerifyOnly) {
    if (-not (Test-Path $stateFile)) { throw "No smoke-state.json found. Run the full smoke test first." }
    $state = Get-Content $stateFile -Raw | ConvertFrom-Json
    Write-Host "Verifying persistence for appointment $($state.apptId) on $($state.date)..."

    $patientPassword = Read-Host "Patient password for $($state.patientEmail)" -AsSecureString
    $patientPlain = [System.Net.NetworkCredential]::new("", $patientPassword).Password
    $patient = Login $state.patientEmail $patientPlain
    $mine = (Call "GET" "/api/appointments/my" $patient.token $null).Json
    $row = $mine | Where-Object { $_.id -eq $state.apptId }
    Check "appointment survives backend restart with status COMPLETED" ($row -and $row.status -eq "COMPLETED")

    $adminPassword = Read-Host "Admin password (BOOTSTRAP_ADMIN_PASSWORD)" -AsSecureString
    $adminPlain = [System.Net.NetworkCredential]::new("", $adminPassword).Password
    $admin = Login "admin@carequeue.com" $adminPlain
    $analytics = (Call "GET" "/api/analytics/overview?date=$($state.date)" $admin.token $null).Json
    Check "analytics still count the completed visit after restart" ($analytics.completedToday -ge 1)

    if ($script:failures -gt 0) { Write-Host "$($script:failures) check(s) failed." -ForegroundColor Red; exit 1 }
    Write-Host "Persistence verified." -ForegroundColor Green
    exit 0
}

# ---------------------------------------------------------------- full workflow

$stamp = Get-Date -Format "yyyyMMddHHmmss"
$doctorEmail = "smoke.doctor.$stamp@example.com"
$receptionEmail = "smoke.reception.$stamp@example.com"
$patientEmail = "smoke.patient.$stamp@example.com"
$doctorPassword = "Smoke#Doc$stamp"
$receptionPassword = "Smoke#Rec$stamp"
$patientPassword = "Smoke#Pat$stamp"

$adminPassword = Read-Host "BOOTSTRAP_ADMIN_PASSWORD (from .env)" -AsSecureString
$adminPlain = [System.Net.NetworkCredential]::new("", $adminPassword).Password

$frontendCheck = $false
try { $fe = Invoke-WebRequest -Uri "$FrontendUrl/api/hospitals" -UseBasicParsing; $frontendCheck = ($fe.StatusCode -eq 200) } catch { $frontendCheck = $false }
Check "frontend (nginx on $FrontendUrl) reaches the backend through /api" $frontendCheck

$admin = Login "admin@carequeue.com" $adminPlain
Check "admin login returns the ADMIN role" ($admin.role -eq "ADMIN")

$hospitals = (Call "GET" "/api/hospitals" $admin.token $null).Json
Check "production seed created at least one hospital" ($hospitals.Count -ge 1)
$hospitalId = $hospitals[0].id

$departments = (Call "GET" "/api/hospitals/$hospitalId/departments" $admin.token $null).Json
Check "production seed created departments" ($departments.Count -ge 1)
$departmentId = $departments[0].id

$schedule = @()
foreach ($day in "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY") {
    $schedule += @{
        dayOfWeek           = $day
        startTime           = "00:00:00"
        endTime             = "23:45:00"
        slotDurationMinutes = 15
        maxPatients         = 40
        active              = $true
    }
}

$doctorResponse = Call "POST" "/api/admin/doctors" $admin.token @{
    fullName       = "Smoke Doctor $stamp"
    email          = $doctorEmail
    password       = $doctorPassword
    phoneNumber    = "9000$($stamp.Substring(8))"
    hospitalId     = $hospitalId
    departmentId   = $departmentId
    licenseNumber  = "LIC-SMOKE-$stamp"
    specialization = "Smoke testing"
    initialSchedule = $schedule
}
Check "admin creates a doctor with a schedule (HTTP 200)" ($doctorResponse.Status -eq 200)
$doctorId = $doctorResponse.Json.id

$receptionResponse = Call "POST" "/api/admin/receptionists" $admin.token @{
    fullName    = "Smoke Reception $stamp"
    email       = $receptionEmail
    password    = $receptionPassword
    phoneNumber = "9100$($stamp.Substring(8))"
    hospitalId  = $hospitalId
}
Check "admin creates a receptionist (HTTP 200)" ($receptionResponse.Status -eq 200)

$doctor = Login $doctorEmail $doctorPassword
$reception = Login $receptionEmail $receptionPassword
Check "doctor login carries the doctor profile id" ($doctor.doctorId -eq $doctorId)

$signup = Call "POST" "/api/auth/register" $null @{
    fullName    = "Smoke Patient $stamp"
    email       = $patientEmail
    password    = $patientPassword
    phoneNumber = "9200$($stamp.Substring(8))"
}
Check "patient signup succeeds (HTTP 200)" ($signup.Status -eq 200)
$patient = Login $patientEmail $patientPassword
Check "patient login resolves to the signup patient profile" ($patient.patientId -eq $signup.Json.patientId)

$slot = Next-BookableSlot
$date = $slot.ToString("yyyy-MM-dd")
$time = $slot.ToString("HH:mm:ss")
$booking = Call "POST" "/api/appointments" $patient.token @{
    hospitalId       = $hospitalId
    departmentId     = $departmentId
    doctorId         = $doctorId
    appointmentDate  = $date
    appointmentTime  = $time
    patientNotes     = "smoke test"
}
Check "patient books an available slot (HTTP 200, CONFIRMED)" ($booking.Status -eq 200 -and $booking.Json.status -eq "CONFIRMED")
$apptId = $booking.Json.id

$duplicate = Call "POST" "/api/appointments" $patient.token @{
    hospitalId = $hospitalId; departmentId = $departmentId; doctorId = $doctorId
    appointmentDate = $date; appointmentTime = $time
}
Check "a second booking for the same slot is refused (HTTP 409)" ($duplicate.Status -eq 409)

$earlyCall = Call "POST" "/api/queue/doctor/$doctorId/call-next" $doctor.token $null
Check "call-next before check-in is refused (HTTP 400)" ($earlyCall.Status -eq 400)

$patientCheckIn = Call "POST" "/api/queue/reception/check-in/$apptId" $patient.token $null
Check "a patient cannot check themselves in (HTTP 403)" ($patientCheckIn.Status -eq 403)

$checkIn = Call "POST" "/api/queue/reception/check-in/$apptId" $reception.token $null
Check "receptionist checks the patient in (HTTP 200, WAITING)" ($checkIn.Status -eq 200 -and $checkIn.Json.status -eq "WAITING")
$entryId = $checkIn.Json.id

$called = Call "POST" "/api/queue/doctor/$doctorId/call-next" $doctor.token $null
Check "doctor calls the checked-in patient" ($called.Status -eq 200 -and $called.Json.currentPatient.id -eq $entryId)

$started = Call "POST" "/api/queue/doctor/$doctorId/start/$entryId" $doctor.token $null
Check "doctor starts the consultation (HTTP 200)" ($started.Status -eq 200)

$completed = Call "POST" "/api/queue/doctor/$doctorId/complete/$entryId" $doctor.token @{}
Check "doctor completes the consultation (HTTP 200)" ($completed.Status -eq 200)

$mine = (Call "GET" "/api/appointments/my" $patient.token $null).Json
$row = $mine | Where-Object { $_.id -eq $apptId }
Check "patient sees the appointment as COMPLETED" ($row -and $row.status -eq "COMPLETED")

$analytics = (Call "GET" "/api/analytics/overview?date=$date" $reception.token $null).Json
Check "receptionist analytics count the completed visit" ($analytics.completedToday -ge 1)

$badToken = Call "GET" "/api/appointments/my" "not.a.jwt" $null
Check "an invalid JWT is rejected (HTTP 401)" ($badToken.Status -eq 401)

$patientOnAdmin = Call "GET" "/api/admin/audit-logs" $patient.token $null
Write-Host "DEBUG: Patient audit-log HTTP status = $($patientOnAdmin.Status)"
Check "a patient cannot read admin audit logs (HTTP 403)" ($patientOnAdmin.Status -eq 403)

@{ date = $date; apptId = $apptId; doctorId = $doctorId; patientEmail = $patientEmail } |
    ConvertTo-Json | Set-Content -Path $stateFile -Encoding UTF8
Write-Host ""
Write-Host "State saved (no secrets) to smoke-state.json for the restart check."
Write-Host "Next: docker compose restart backend ; then run this script with -VerifyOnly"

if ($script:failures -gt 0) {
    Write-Host "$($script:failures) check(s) failed." -ForegroundColor Red
    exit 1
}
Write-Host "All smoke checks passed." -ForegroundColor Green
exit 0
