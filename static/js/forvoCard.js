function playForvoAudioStream(word, streamPath) {
    $.get('/forvoVoicesCard/playAudioStream.html?word=' + word + '&streamPath=' + streamPath)
        .error(function (jqXHR, textStatus, errorThrown) {
            // TODO
            // showErrorMessage("LinguaLeo", "There was a server error occurred while play audio from Forvo: (" + jqXHR.status + ") " + errorThrown, true);
        });
}