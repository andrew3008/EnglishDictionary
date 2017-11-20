function playOALD9SoundFile(fileName) {
    $.get('/wordCard/playOALD9SoundFile.html?fileName=' + fileName)
        .error(function (err) {
            alert("Произошла серверная ошибка при озвучивании слова:\\n" + err.toString());
        });
    return false;
}

function playLDOCE6SoundFile(fileName) {
    $.get('/wordCard/playLDOCE6SoundFile.html?fileName=' + fileName)
        .error(function (err) {
            alert("Произошла серверная ошибка при озвучивании слова:\\n" + err.toString());
        });
    return false;
}

var wordCardBaseURL = "";
var activeWordCardFrame;

function setWordCardBaseURL(baseURL) {
    wordCardBaseURL = baseURL;
    activeWordCardFrame = this;
    console.info(baseURL);
}

function goToWord(dictionaryName, word) {
    parent.document.getElementById(dictionaryName).src = $(parent.document.getElementById(dictionaryName)).attr('wordCardBaseURL') + word.replace(new RegExp(" ", 'g'), "%20") + "&wholeWord=true";
}

function gdExpandOptPart(expanderId, optionalId) {
    var d1 = document.getElementById(expanderId);
    var i = 0;
    if (d1.alt == '[+]') {
        d1.alt = '[-]';
        d1.src = '/static/images/wordCard/collapse_opt.png';
        for (i = 0; i < 1000; i++) {
            var d2 = document.getElementById(optionalId + i);
            if (!d2) break;
            d2.style.display = 'inline';
        }
    }
    else {
        d1.alt = '[+]';
        d1.src = '/static/images/wordCard//expand_opt.png';
        for (i = 0; i < 1000; i++) {
            var d2 = document.getElementById(optionalId + i);
            if (!d2) break;
            d2.style.display = 'none';
        }
    }
}

function gdExpandArticle(id) {
    elem = document.getElementById('gdarticlefrom-' + id);
    ico = document.getElementById('expandicon-' + id);
    art = document.getElementById('gdfrom-' + id);
    ev = window.event;
    t = null;
    if (ev) t = ev.target || ev.srcElement;
    if (elem.style.display == 'inline' && t == ico) {
        elem.style.display = 'none';
        ico.className = 'gdexpandicon';
        art.className = art.className + ' gdcollapsedarticle';
        nm = document.getElementById('gddictname-' + id);
        nm.style.cursor = 'pointer';
        if (ev) ev.stopPropagation();
        ico.title = '';
        nm.title = 'Expand article'
    }
    else if (elem.style.display == 'none') {
        elem.style.display = 'inline';
        ico.className = 'gdcollapseicon';
        art.className = art.className.replace(' gdcollapsedarticle', '');
        nm = document.getElementById('gddictname-' + id);
        nm.style.cursor = 'default';
        nm.title = '';
        ico.title = 'Collapse article'
    }
}

function AXv(c, n) {
    with (c.parentNode) {
        for (var i = 0; i < childNodes.length; i++) childNodes[i].className = "ny4";
        c.className = "aqE";
        if (n > 0) childNodes[0].className = "aQM";
    }
}
