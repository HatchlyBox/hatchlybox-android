#!/bin/sh
# The weather where the phone is (found from its internet address by wttr.in, no key): now, and the next three days.
out=$(curl -fsS --max-time 20 'https://wttr.in/?format=j1') || { echo '{"error":"No connection to wttr.in"}'; exit 0; }
printf '%s' "$out" | jq -c --arg updated "$(date +%H:%M)" '
  def icon: ascii_downcase | if test("thunder") then "bolt" elif test("rain|drizzle|shower") then "rain" elif test("snow|sleet|ice") then "snow"
    elif test("sun|clear") then "sun" elif test("fog|mist") then "water" else "cloud" end;
  def day: strptime("%Y-%m-%d") | mktime | strftime("%a");
  .current_condition[0] as $c | {
  temp: $c.temp_C, feels: $c.FeelsLikeC, desc: $c.weatherDesc[0].value, icon: ($c.weatherDesc[0].value | icon),
  wind: $c.windspeedKmph, humidity: $c.humidity, rain: (.weather[0].hourly | map(.chanceofrain | tonumber) | max),
  place: .nearest_area[0].areaName[0].value, hi: .weather[0].maxtempC, lo: .weather[0].mintempC,
  hours: [.weather[0].hourly[].tempC | tonumber],
  hourly: [.weather[0].hourly[] | {time: (.time | tonumber / 100 | floor | tostring | if length < 2 then "0" + . else . end + ":00"), temp: .tempC, rain: .chanceofrain, desc: .weatherDesc[0].value}],
  days: [.weather[] | {day: (.date | day), hi: .maxtempC, lo: .mintempC, icon: (.hourly[4].weatherDesc[0].value | icon)}],
  updated: $updated, error: ""
}' 2>/dev/null || echo '{"error":"wttr.in sent something unexpected"}'
