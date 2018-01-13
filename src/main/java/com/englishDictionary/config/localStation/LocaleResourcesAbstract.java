package com.englishDictionary.config.localStation;

import com.englishDictionary.config.EnvironmentResourcesInterface;

abstract class LocaleResourcesAbstract implements EnvironmentResourcesInterface {

    @Override
    public String getCommonImagesDir() {
        return getResourcesRootDir() + "\\CommonImages\\";
    }

    @Override
    public String getWordCardsImagesDir() {
        return getResourcesRootDir() + "\\WordCards\\";
    }

    @Override
    public String getDictionariesDir() {
        return getResourcesRootDir() + "\\Dictionaries\\";
    }
    @Override
    public String getDigitalDictionariesDir() {
        return getDictionariesDir() + "DigitalDictionaries\\";
    }

    @Override
    public String getLingvoUniversalDir() {
        return getDictionariesDir() + "LingvoUniversal\\";
    }
    @Override
    public String getLingvoUniversalSoundDatFilePath() {
        return getLingvoUniversalDir() + "Sounds\\SoundEn.dat";
    }
    @Override
    public String getLingvoUniversalSoundIndFilePath() {
        return getLingvoUniversalDir() + "Sounds\\SoundEn.ind";
    }

    @Override
    public String getMED2Dir() {
        return getDictionariesDir() + "MED2\\";
    }
    @Override
    public String getMED2WordCardHeadersFilePath() {
        return getMED2Dir() + "WordCardHeaders\\WordCardHeaders.dat";
    }

    @Override
    public String getOALD9Dir() {
        return getDictionariesDir() + "OALD9\\";
    }
    @Override
    public String getOALD9ImagesDir() {
        return getOALD9Dir() + "Images\\";
    }
    @Override
    public String getOALD9SoundDatFilePath() {
        return getOALD9Dir() + "Sounds\\SoundEn.dat";
    }
    @Override
    public String getOALD9SoundIndFilePath() {
        return getOALD9Dir() + "Sounds\\SoundEn.ind";
    }
    @Override
    public String getOALD9TranscriptionsFilePath() {
        return getOALD9Dir() + "\\Transcriptions\\Transcriptions.dat";
    }

    @Override
    public String getLDOCE6Dir() {
        return getDictionariesDir() + "LDOCE6\\";
    }
    @Override
    public String getLDOCE6ImagesDir() {
        return getLDOCE6Dir() + "Images\\";
    }
    @Override
    public String getLDOCE6SoundDatFilePath() {
        return getLDOCE6Dir() + "Sounds\\SoundEn.dat";
    }
    @Override
    public String getLDOCE6SoundIndFileParh() {
        return getLDOCE6Dir() + "Sounds\\SoundEn.ind";
    }

    @Override
    public String getIrregularVerbsFilePath() {
        return getDictionariesDir() + "\\IrregularVerbs\\IrregularVerbs.dat";
    }

    @Override
    public String getMnemonicsDir() {
        return getResourcesRootDir() + "\\Mnemonics\\";
    }
    @Override
    public String getMnemonicsFlagsDir() {
        return getMnemonicsDir() + "Flags\\";
    }
    @Override
    public String getMnemonicsImagesDir() {
        return getMnemonicsDir() + "Images\\";
    }
    @Override
    public String getMnemonicsFilePath() {
        return getMnemonicsDir() + "Mnemonics.dat";
    }

    @Override
    public String getForvoDir() {
        return getResourcesRootDir() + "\\WebServices\\Forvo\\";
    }

}
