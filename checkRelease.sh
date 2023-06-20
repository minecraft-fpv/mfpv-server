#! /bin/bash

echo A | unzip build/libs/fpvdrone-1.16.2-1.4.0.jar -d build/libs/unpacked
java -jar jd-cli.jar build/libs/unpacked/com/gluecode/fpvdrone/input/a.class > build/libs/InputHandler.class
cat build/libs/InputHandler.class

#java -jar jd-cli.jar build/libs/unpacked/com/gluecode/fpvdrone/gui/FpvKeyBindingList.class >
# build/libs/FpvKeyBindingList.class
#cat build/libs/FpvKeyBindingList.class