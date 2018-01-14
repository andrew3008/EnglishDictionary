package com.englishDictionary.config.openShiftClaster;

import com.englishDictionary.config.EnvironmentResourcesInterface;

public class ConfigOpenShiftCluster implements EnvironmentResourcesInterface {

    @Override
    public String getResourcesRootDir() {
        return "";
    }

    @Override
    public String getCommonImagesDir() {
        return "/tmp/src/static/openshift/CommonImages/";
    }

    @Override
    public String getWordCardsImagesDir() {
        return "/tmp/src/static/openshift/WordCards/";
    }

    @Override
    public String getDictionariesDir() {
        return "/tmp/src/static/openshift/Dictionaries/";
    }

    @Override
    public String getDigitalDictionariesDir() {
        return "";
    }

    @Override
    public String getLingvoUniversalDir() {
        return "/tmp/src/static/openshift/Dictionaries/LingvoUniversal/";
    }

    @Override
    public String getLingvoUniversalSoundDatFilePath() {
        return null;
    }

    @Override
    public String getLingvoUniversalSoundIndFilePath() {
        return null;
    }

    @Override
    public String getMED2Dir() {
        return getDictionariesDir() + "MED2\\";
    }

    @Override
    public String getMED2WordCardHeadersFilePath() {
        return "/tmp/src/static/openshift/Dictionaries/MED2/WordCardHeaders/WordCardHeaders.dat";
    }

    @Override
    public String getOALD9Dir() {
        return "/tmp/src/static/openshift/Dictionaries/OALD9/";
    }

    @Override
    public String getOALD9ImagesDir() {
        return getOALD9Dir() + "Images/";
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
        return getOALD9Dir() + "/Transcriptions/Transcriptions.dat";
    }

    @Override
    public String getLDOCE6Dir() {
        return "/tmp/src/static/openshift/Dictionaries/LDOCE6/";
    }

    @Override
    public String getLDOCE6ImagesDir() {
        return getLDOCE6Dir() + "Images/";
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
        return "/tmp/src/static/openshift/Dictionaries/IrregularVerbs/IrregularVerbs.dat";
    }

    @Override
    public String getMnemonicsDir() {
        return "/tmp/src/static/openshift/Mnemonics/";
    }

    @Override
    public String getMnemonicsFlagsDir() {
        return getMnemonicsDir() + "Flags/";
    }

    @Override
    public String getMnemonicsImagesDir() {
        return getMnemonicsDir() + "Images/";
    }

    @Override
    public String getMnemonicsFilePath() {
        return getMnemonicsDir() + "Mnemonics.dat";
    }

    @Override
    public String getForvoDir() {
        return "/tmp/src/static/openshift/WebServices/Forvo/";
    }

    @Override
    public String getWordsFilesDir() {
        return "";
    }

    @Override
    public String getFileNameOfWordsFromExcel() {
        return null;
    }

}
