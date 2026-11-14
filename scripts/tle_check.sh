#!/bin/bash
echo "=== HTTP headers ==="
for f in satnogs.3le amateur.3le active.csv iss.3le; do
  printf "  /tle/%s: " "$f"
  curl -sI "https://hamkit.click/tle/$f" | grep -iE "HTTP/|Content-Type|Content-Length|Content-Encoding" | tr "\n" " "
  echo
done
echo "=== gzip comparison ==="
curl -s -H "Accept-Encoding: gzip" -o /dev/null -w "satnogs gzip: %{size_download}B code %{http_code}\n" https://hamkit.click/tle/satnogs.3le
curl -s -o /dev/null -w "satnogs plain:  %{size_download}B code %{http_code}\n" https://hamkit.click/tle/satnogs.3le
echo "=== active.csv gzip ==="
curl -s -H "Accept-Encoding: gzip" -o /dev/null -w "active gzip:  %{size_download}B code %{http_code}\n" https://hamkit.click/tle/active.csv
curl -s -o /dev/null -w "active plain:  %{size_download}B code %{http_code}\n" https://hamkit.click/tle/active.csv
echo "=== done ==="