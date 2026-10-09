#!/bin/sh
# Drive DuoBoy Advance on the Thor for testing.
#   thor.sh key A|B|START|SELECT|UP|DOWN|LEFT|RIGHT|L|R [frames]   hold a button for N frames (default 6)
#   thor.sh tap X Y          tap the bottom screen at canvas coords (248x216)
#   thor.sh shot NAME        screenshots -> $SHOTS/NAME_top.png, NAME_bot.png
#   thor.sh save|load SLOT   save state / load save state
#   thor.sh wild SPECIES LV  start a wild battle (in the field)
A=$(adb devices | awk 'NR==2{print $1}')
OUT="${SHOTS:-/tmp}"
case "$1" in
  key) case "$2" in A) k=1;; B) k=2;; SELECT) k=4;; START) k=8;; RIGHT) k=16;; LEFT) k=32;; UP) k=64;; DOWN) k=128;; R) k=256;; L) k=512;; esac
       adb -s $A shell am broadcast -a dev.gbads.KEY --ei k $k --ei f ${3:-6} >/dev/null ;;
  tap) adb -s $A shell input -d 4 tap $(($2*5)) $(($3*5)) ;;
  shot) adb -s $A exec-out screencap -p -d 4630946441858561667 > "$OUT/$2_top.png"; adb -s $A exec-out screencap -p -d 4630946482288158084 > "$OUT/$2_bot.png" ;;
  save|load) adb -s $A shell am broadcast -a dev.gbads.STATE --es op $1 --ei slot ${2:-0} >/dev/null; sleep 0.5; adb -s $A logcat -d -t 20 | grep "state $1" | tail -1 ;;
esac
case "$1" in poke) adb -s $A shell am broadcast -a dev.gbads.POKE --ei a $(($2)) --ei v $(($3)) >/dev/null ;;
  wild) adb -s $A shell am broadcast -a dev.gbads.WILD --ei sp $2 --ei lv ${3:-5} >/dev/null ;; esac
