#!/bin/sh
set -eu
wget -qO- "http://127.0.0.1:8080/dashboard?lang=tr" > /tmp/dashboard.html
echo "--- ids ---"
grep -o 'id="[^"]*"' /tmp/dashboard.html | head -n 60
echo "--- section order ---"
grep -n 'primary-signals\|supporting-signals\|signal-dashboard\|metric-taxonomy' /tmp/dashboard.html
