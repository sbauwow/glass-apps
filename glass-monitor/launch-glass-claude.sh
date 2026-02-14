#!/bin/bash
# Launch a urxvt terminal sized for the GLASS virtual monitor,
# positioned at the GLASS region, running claude in ~/secbutler/

GLASS_X=3880
GLASS_Y=1080
COLS=80
ROWS=20

urxvt \
  -geometry "${COLS}x${ROWS}+${GLASS_X}+${GLASS_Y}" \
  -cd /home/stathis/secbutler \
  -e /home/stathis/.local/bin/claude &
