# FieldTracks (Android, sideload)

Logs the phone's location every N minutes inside a daily time window and writes
`fieldtracks_yyyymmdd.json`. No Play Store; APK is built by `.github/workflows/build.yml`.

Install: on the phone open `https://github.com/forestsoil/fieldtracks/releases/tag/fieldtracks-latest`, download `FieldTracks.apk`, allow "install unknown apps".
First run: Grant permissions (choose "Allow all the time"), Battery: unrestricted, Save settings.

Defaults: 07:00-19:00, every 5 min (1-99), saved to `Documents/FieldTracks/`.

Optional stable signing (so updates install over old versions):
```
keytool -genkeypair -v -keystore ft.jks -alias ft -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 ft.jks | gh secret set FT_KEYSTORE_B64 -R forestsoil/fieldtracks
gh secret set FT_KS_PASS -R forestsoil/fieldtracks; gh secret set FT_KEY_ALIAS -R forestsoil/fieldtracks; gh secret set FT_KEY_PASS -R forestsoil/fieldtracks
```

## File format
```json
{"schema":"fieldtracks/1","date":"2026-11-12","tz":"+05:30","device":"...",
 "points":[{"t":"2026-11-12T07:05:00+05:30","lat":26.7,"lon":88.4,"acc":8.2,"alt":120.5,"spd":0.4,"src":"gps"}]}
```
`src`: gps | net | last (cached fix <10 min old). Missed fixes are not logged (counted in the app).

## Reading from the PWA
```html
<input type="file" accept=".json,application/json" multiple id="ft">
<script>ft.onchange=async e=>{for(const f of e.target.files){const j=JSON.parse(await f.text());/* j.points */}}</script>
```
In the Android picker open **Documents > FieldTracks**.
