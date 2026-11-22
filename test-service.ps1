<#
  Test automatique du service de surveillance (proctoring-service).
  Il envoie de vrais evenements et verifie chaque reponse. [OK] = comme prevu, [ECHEC] = probleme.

  MODE A - sans Node (le plus simple, ne depend que de Spring) :
     .\test-service.ps1 -JwtSecret "<meme valeur que JWT_SECRET du .env de Node>" -ServiceKey "<PROCTORING_SERVICE_KEY>"

  MODE B - avec Node (connexion reelle avec une cle d'invitation) :
     .\test-service.ps1 -Cle "PSY-XXXXXX" -ServiceKey "<PROCTORING_SERVICE_KEY>"

  Si PowerShell refuse d'executer le script :
     powershell -ExecutionPolicy Bypass -File .\test-service.ps1 -JwtSecret "..." -ServiceKey "..."
#>
param(
  [string]$SpringUrl = "http://localhost:8081",
  [string]$NodeUrl = "http://localhost:3000",
  [string]$JwtSecret = "",
  [string]$Cle = "",
  [Parameter(Mandatory = $true)][string]$ServiceKey
)

$script:ok = 0
$script:ko = 0

function Titre($t) { Write-Host ""; Write-Host "=== $t" -ForegroundColor Cyan }

function Verifier($nom, [bool]$condition, $detail = "") {
  if ($condition) { Write-Host "  [OK]     $nom $detail" -ForegroundColor Green; $script:ok++ }
  else { Write-Host "  [ECHEC]  $nom $detail" -ForegroundColor Red; $script:ko++ }
}

# Envoie une requete HTTP et retourne @{ Code = code HTTP; Data = reponse JSON (ou $null) }
function Appel($methode, $url, $headers = @{}, $corps = $null) {
  try {
    $p = @{ Method = $methode; Uri = $url; Headers = $headers; UseBasicParsing = $true }
    if ($null -ne $corps) { $p.Body = ($corps | ConvertTo-Json -Compress); $p.ContentType = "application/json" }
    $r = Invoke-WebRequest @p
    $data = $null
    if ($r.Content) { try { $data = $r.Content | ConvertFrom-Json } catch { } }
    return @{ Code = [int]$r.StatusCode; Data = $data }
  }
  catch {
    $code = 0
    if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
    return @{ Code = $code; Data = $null }
  }
}

function B64Url([byte[]]$octets) {
  return [Convert]::ToBase64String($octets).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

# Fabrique un JWT HS256 comme le fait Node (mode A uniquement)
function New-Jwt($secret, $invitation) {
  $header = B64Url ([Text.Encoding]::UTF8.GetBytes('{"alg":"HS256","typ":"JWT"}'))
  $exp = [DateTimeOffset]::UtcNow.AddHours(1).ToUnixTimeSeconds()
  $payload = B64Url ([Text.Encoding]::UTF8.GetBytes('{"sub":' + $invitation + ',"role":"candidat","exp":' + $exp + '}'))
  $hmac = New-Object System.Security.Cryptography.HMACSHA256
  $hmac.Key = [Text.Encoding]::UTF8.GetBytes($secret)
  $sig = B64Url ($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes("$header.$payload")))
  return "$header.$payload.$sig"
}

# Lit le numero d'invitation (champ "sub") dans un JWT
function Lire-Sub($jwt) {
  $p = $jwt.Split('.')[1].Replace('-', '+').Replace('_', '/')
  while ($p.Length % 4) { $p += '=' }
  $json = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($p)) | ConvertFrom-Json
  return [long]$json.sub
}

if ($JwtSecret -eq "" -and $Cle -eq "") {
  Write-Host "Donne soit -JwtSecret (mode A), soit -Cle (mode B). Voir l'en-tete du script." -ForegroundColor Yellow
  exit 1
}

# ---------------------------------------------------------------
Titre "1. Le service Spring est-il demarre ?"
$h = Appel GET "$SpringUrl/api/health"
Verifier "GET /api/health repond 200" ($h.Code -eq 200) "(code recu : $($h.Code))"
if ($h.Code -ne 200) {
  Write-Host "  Spring ne repond pas sur $SpringUrl. Demarre-le (IntelliJ ou mvn spring-boot:run) puis relance ce script." -ForegroundColor Yellow
  exit 1
}

# ---------------------------------------------------------------
Titre "2. Obtention du badge (token JWT) du candidat"
if ($JwtSecret -ne "") {
  $invitation = Get-Random -Minimum 100000 -Maximum 999999
  $token = New-Jwt $JwtSecret $invitation
  Write-Host "  Mode A : token fabrique localement pour l'invitation no.$invitation"
}
else {
  $login = Appel POST "$NodeUrl/api/candidat/auth/login" @{} @{ cle = $Cle }
  Verifier "Connexion a Node avec la cle $Cle" ($login.Code -eq 200 -and $login.Data.token) "(code $($login.Code))"
  if (-not $login.Data.token) { Write-Host "  Node ne repond pas ou cle invalide. Verifie que Node tourne sur $NodeUrl." -ForegroundColor Yellow; exit 1 }
  $token = $login.Data.token
  $invitation = Lire-Sub $token
  Write-Host "  Mode B : token recu de Node, invitation no.$invitation"
}
$auth = @{ Authorization = "Bearer $token" }

# ---------------------------------------------------------------
Titre "3. Securite : les mauvais acces doivent etre refuses"
$r = Appel POST "$SpringUrl/api/events" @{} @{ type = "APP_BACKGROUND" }
Verifier "Sans token -> 401" ($r.Code -eq 401) "(code $($r.Code))"
$r = Appel POST "$SpringUrl/api/events" @{ Authorization = "Bearer faux.token.xyz" } @{ type = "APP_BACKGROUND" }
Verifier "Faux token -> 401" ($r.Code -eq 401) "(code $($r.Code))"
$r = Appel GET "$SpringUrl/api/sessions/$invitation/report" @{ "X-Service-Key" = "mauvaise-cle" }
Verifier "Rapport avec une mauvaise cle de service -> 401" ($r.Code -eq 401) "(code $($r.Code))"
$r = Appel GET "$SpringUrl/api/sessions/$invitation/report" $auth
Verifier "Le candidat ne peut pas lire son propre rapport -> 401" ($r.Code -eq 401) "(code $($r.Code))"

# ---------------------------------------------------------------
Titre "4. Demarrage de la surveillance (POST /api/sessions)"
$r = Appel POST "$SpringUrl/api/sessions" $auth @{}
Verifier "Session creee -> 200" ($r.Code -eq 200) "(code $($r.Code))"
if ($r.Data) { Write-Host "  Pays de depart detecte par l'API externe : '$($r.Data.paysDepart)' (vide si l'API est injoignable : ce n'est pas bloquant)" }

# ---------------------------------------------------------------
Titre "5. Envoi d'evenements (POST /api/events) : un candidat suspect"
$evenements = @(
  @{ type = "APP_BACKGROUND"; questionId = 5 },
  @{ type = "APP_RESUME"; questionId = 5 },
  @{ type = "APP_BACKGROUND"; questionId = 6 },
  @{ type = "APP_RESUME"; questionId = 6 },
  @{ type = "SCREENSHOT"; questionId = 3 }
)
$lettres = "A", "C", "B", "D", "B", "A", "C", "D"
for ($i = 0; $i -lt 8; $i++) {
  $evenements += @{ type = "ANSWER"; questionId = ($i + 1); choix = $lettres[$i]; tempsMs = 800 }
}
$acceptes = 0
foreach ($e in $evenements) {
  $r = Appel POST "$SpringUrl/api/events" $auth $e
  if ($r.Code -eq 202) { $acceptes++ }
}
Verifier "$($evenements.Count) evenements envoyes, tous acceptes (202)" ($acceptes -eq $evenements.Count) "($acceptes acceptes)"

# ---------------------------------------------------------------
Titre "6. Validation : les donnees incorrectes doivent etre refusees"
$r = Appel POST "$SpringUrl/api/events" $auth @{ type = "BLABLA" }
Verifier "Type d'evenement inconnu -> 400" ($r.Code -eq 400) "(code $($r.Code))"
$r = Appel POST "$SpringUrl/api/events" $auth @{ type = "ANSWER"; questionId = 1; choix = "Z"; tempsMs = 500 }
Verifier "Choix 'Z' (hors A-D) -> 400" ($r.Code -eq 400) "(code $($r.Code))"

# ---------------------------------------------------------------
Titre "7. Rapport de risque (GET /api/sessions/$invitation/report avec la cle de service)"
$r = Appel GET "$SpringUrl/api/sessions/$invitation/report" @{ "X-Service-Key" = $ServiceKey }
Verifier "Rapport obtenu -> 200" ($r.Code -eq 200) "(code $($r.Code) ; si 401, la cle -ServiceKey n'est pas celle utilisee par Spring)"
if ($r.Data) {
  Write-Host "  Score de risque : $($r.Data.scoreRisque)/100  -  niveau $($r.Data.niveau)  -  $($r.Data.nbEvenements) evenements" -ForegroundColor Yellow
  foreach ($a in $r.Data.alertes) { Write-Host ("    +{0,2} pts  {1,-17} {2}" -f $a.points, $a.regle, $a.detail) }
  if ($JwtSecret -ne "") {
    Verifier "Score attendu = 50 (2 sorties:10 + 1 capture:20 + reponses rapides:20)" ($r.Data.scoreRisque -eq 50) "(obtenu $($r.Data.scoreRisque))"
    Verifier "Niveau attendu = MOYEN" ($r.Data.niveau -eq "MOYEN") "(obtenu $($r.Data.niveau))"
  }
  else {
    Verifier "Score au moins 50" ($r.Data.scoreRisque -ge 50) "(obtenu $($r.Data.scoreRisque) ; plus haut si tu as relance le test sur la meme invitation)"
  }
}
$r = Appel GET "$SpringUrl/api/sessions/99999999/report" @{ "X-Service-Key" = $ServiceKey }
Verifier "Candidat inconnu -> 404" ($r.Code -eq 404) "(code $($r.Code))"

# ---------------------------------------------------------------
Titre "RESULTAT"
$color = "Green"; if ($script:ko -gt 0) { $color = "Red" }
Write-Host "  $($script:ok) verifications reussies, $($script:ko) echec(s)" -ForegroundColor $color
Write-Host ""
Write-Host "Maintenant, regarde la fenetre de Spring (console IntelliJ) : tu dois y voir les etapes" -ForegroundColor Cyan
Write-Host "  [1/4 SECURITE] -> [2/4 CONTROLEUR] -> [3/4 SERVICE] -> [4/4 BASE], puis [RAPPORT] et [SCORE]."
Write-Host "Et dans la base : http://localhost:8081/h2-console  ->  SELECT * FROM PROCTOR_EVENT WHERE INVITATION_ID = $invitation;"
