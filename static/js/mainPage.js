var selectContentFileWords = document.getElementById("contentFileWords");
var tableWords;
var tableWordsScrollYElement;
var tableWordsLastScrollPosition = -1;
var mapGroupWords = new Object();
var buttonCommentsIndex = 3;
var needToShowKeyWords = false;
var redrawRightWordCard = null;

$(function () {
     tableWords = $('#tableWords').DataTable(
        {
            "language": {
                "emptyTable": "Table is empty"
            },
            "columns": [
                {"title": "HaseMnemonics", visible: false},
                {"title": "Group", visible: false},
                {"title": "Word", "width": "12%"},
                {"title": "Transcription", "width": "20%"},
                {
                    "title": "WC", "width": "1px",
                    "render": function (data, type, row) {
                        var word = row[2];
                        if (isEmptyString(word)) {
                            return "";
                        } else {
                            return "<a class=\"btn btn-primary smallButtonDataTables\" style=\"margin-left: 8px;\" word_of_card=\"" + word + "\" >" +
                                "<img src=\"/static/images/wordCard/word-card.png\" class=\"iconSmallButtonDataTables\">" +
                                "</a>";
                        }
                    }
                },
                {
                    "title": "FV", "width": "1px",
                    "render": function (data, type, row) {
                        var word = row[2];
                        if (isEmptyString(word)) {
                            return "";
                        } else {
                            return "<a class=\"btn btn-primary smallButtonDataTables\" style=\"margin-left: 5px;\" word_of_forvo_card=\"" + word + "\" >" +
                                "<img src=\"/static/images/forvo.png\" class=\"iconSmallButtonDataTables\">" +
                                "</a>";
                        }
                    }
                },
                {
                    "title": "MN", "width": "1px",
                    "render": function (data, type, row) {
                        var haseMnemonics = row[0];
                        if (haseMnemonics === true) {
                            var word = row[2];
                            return "<a class=\"btn btn-primary smallButtonDataTables\" style=\"margin-left: 5px;\" word_of_mnemonic_card=\"" + word + "\" >" +
                                "<img src=\"/static/images/mnemonics/mnemonics.png\" class=\"iconSmallButtonDataTables\">" +
                                "</a>";
                        } else {
                            return "";
                        }
                    }
                },
                {"title": "Translate", "width": "20%"},
                {"title": "Examples"}
            ],

            dom: 'Bfrtip',
            buttons: [{extend: 'colvis', postfixButtons: ['colvisRestore'], columns: [3, 4, 5, 6, 7, 8]},
                {
                    extend: 'print',
                    autoPrint: false,
                    exportOptions: {
                        columns: ':visible'
                    },
                    customize: function (win) {
                        $(win.document.body)
                            .css('font-size', '12pt')
                            .css('width', '100%')
                            .css('border', '0')
                            .css('margin', '0')
                            .css('padding', '0');

                        $(win.document.body).find('table')
                            .addClass('compact')
                            .css('font-size', 'inherit')
                            .css('width', '100%')
                            .css('border', '0')
                            .css('margin', '0')
                            .css('padding', '0');
                    }
                },
                {
                    text: 'Reload',
                    action: function (e, dt, node, config) {
                        loadListWords();
                    }
                },
                {
                    text: 'Comments',
                    action: function (e, dt, node, config) {
                        var fileName = selectContentFileWords.options[selectContentFileWords.selectedIndex].value;
                        $.get('/comments/load.html?fileName=' + fileName, showComments)
                            .error(function (jqXHR, textStatus, errorThrown) {
                                showErrorMessage('Loading of comments', 'There was a server error when requesting comment for this word list: (' + jqXHR.status + ") " + errorThrown, true);
                            });
                        return false;
                    }
                }
            ],

            "info": false,
            "paging": false,
            "ordering": false,
            "pageLength": 20,
            select: {
                style: 'api',
                info: false
            },
            "scrollY": "570px",
            "scrollCollapse": true,
            stateSave: true,

            "createdRow": function (row, data) {
                $(row).attr('group_words', data[1]);

                var word = data[2];
                if (isEmptyString(word)) {
                    $('td:eq(0)', row).attr('colspan', '8');
                    $('td:eq(1)', row).css('display', 'none');
                    $('td:eq(2)', row).css('display', 'none');
                    $('td:eq(3)', row).css('display', 'none');
                    $('td:eq(4)', row).css('display', 'none');
                    $('td:eq(5)', row).css('display', 'none');
                    $('td:eq(6)', row).css('display', 'none');
                    $('td:eq(7)', row).css('display', 'none');
                    $('td:eq(8)', row).css('display', 'none');
                }
            },

            "drawCallback": function (settings) {
                var api = this.api();
                var rows = api.rows({page: 'current'}).nodes();
                var last = null;

                api.column(1, {page: 'current'}).data().each(function (group, i) {
                    if (group == null) {
                        return false;
                    }

                    var groupName = group.toString().trim();
                    // TODO: Make for different groups when there are groups with other names
                    if (groupName != "") {
                        var groupRows = $(rows).eq(i);
                        if (last !== group) {
                            var iconFileName;
                            if (mapGroupWords[groupName] != null) {
                                iconFileName = (mapGroupWords[groupName] === true) ? "minus.png" : "plus.png";
                            } else {
                                mapGroupWords[groupName] = true;
                                iconFileName = "minus.png";
                            }

                            $(groupRows).before(
                                '<tr class=\"group\"><td colspan=\"' + settings.aoColumns.length + '\">' +
                                '<a class=\"smallButtonDataTablesHeader linkRemoveMessage\" type_lang=2 name_group_words=' + group + ' href=\"#\" >' +
                                '<img src=\"/static/images/' + iconFileName + '\" class=\"iconSmallButtonDataTablesHeader\">' +
                                '</a>' + '&nbsp' + group + '</td></tr>'
                            );

                            last = group;
                        }

                        if (mapGroupWords[groupName] === true) {
                            groupRows.removeClass('hide');
                        } else {
                            groupRows.addClass('hide');
                        }
                    }
                });

                initEventHandlersTableWords();
            }
        });
    tableWordsScrollYElement = document.getElementsByClassName("dataTables_scrollBody")[0];

    $('#contentFileWords').change(function () {
        loadListWords();
    });

    $('#tableWords tbody').on('dblclick click', 'tr', function () {
        if ($(this).hasClass('selectedRow')) {
            $(this).removeClass('selectedRow');
        } else {
            tableWords.$('tr.selectedRow').removeClass('selectedRow');
            $(this).addClass('selectedRow');
        }
    }).on('dblclick', 'tr', function () {
        var row = tableWords.row('.selectedRow').data();
        if (!row)
            return false;

        var wordDescWindows = $("#wordDescriptionWindow");
        wordDescWindows.find(".word").text(row[2]);
        wordDescWindows.find(".translate").text(row[7]);
        wordDescWindows.find(".examples").text(row[8]);
        wordDescWindows.modal("show");
    });

    $('#buttonHideComments').click(function () {
        $('#formComments').addClass("hide");
        tableWords.buttons(buttonCommentsIndex).enable(true);
    });

    loadContent();
    registerHotKeys();
});


/****************************************************************************************************************************************************************************************************************************************/
/*                                                                                                                      List words                                                                                                      */
/****************************************************************************************************************************************************************************************************************************************/
function showListOfSetWord() {
    // TODO: Need to do redirect to "/" whith auto choosen set of words
    $('#tableWords_wrapper').show();
    $('#quizlet_iframe').hide();
    document.getElementById('quizlet_iframe').innerHTML = "";
}

function loadContent() {
    $.get('/index/getContent.html', showContent)
        .error(function (jqXHR, textStatus, errorThrown) {
            showErrorMessage('Loading of content', 'There was a server error when requesting the file list with the words from the server: (' + jqXHR.status + ") " + errorThrown, true);
        })
}

function loadListWords() {
    showListOfSetWord();
    if (selectContentFileWords.selectedIndex != -1) {
        tableWordsLastScrollPosition = tableWordsScrollYElement.scrollTop;
        var fileName = selectContentFileWords.options[selectContentFileWords.selectedIndex].value;
        $.get('/index/getListWords.html?fileName=' + fileName, showListWords)
            .error(function (jqXHR, textStatus, errorThrown) {
                showErrorMessage('Loading of the set of words', 'Error of load words: (' + jqXHR.status + ") " + errorThrown, true);
            });
    }
}

function addOption(oListbox, text, value, isDefaultSelected, isSelected) {
    var oOption = document.createElement("option");
    oOption.appendChild(document.createTextNode(text));
    oOption.setAttribute("value", value);

    if (isDefaultSelected) {
        oOption.defaultSelected = true;
    } else if (isSelected) {
        oOption.selected = true;
    }

    oListbox.appendChild(oOption);
}

function showContent(response) {
    for (var i = 0; i < response.length; ++i) {
        var fileName = response[i].FileName;
        var content = response[i].Content;
        addOption(selectContentFileWords, content, fileName, false, false);
    }

    if (window.location.href.localeCompare("http://localhost:8080/index.html?showQuizletFrame=true") == 0) {
        isLoginned = true;

        $('#tableWords_wrapper').hide();
        $('#quizlet_iframe').show();

        quizletDeleteAllSets( function() {
            quizletExportThisSet( function(responseText) {
                var jsonResponse = JSON.parse(responseText);
                document.getElementById('quizlet_iframe').innerHTML = "<iframe src=\"https://quizlet.com/" + jsonResponse.setId + "/match/embed\" width=\"99%\" style=\"border:0;position: absolute;top: 86px;\"></iframe>";
            });
        });
    }
}

function showListWords(response) {
    tableWords.buttons(buttonCommentsIndex).enable(true);

    tableWords.data().clear();
    for (var i = 0; i < response.length; ++i) {
        tableWords.row.add([
            response[i].haseMnemonics,
            response[i].groupWords,
            response[i].word,
            response[i].transcription,
            "",
            "",
            "",
            response[i].translation,
            response[i].examples]);
    }
    tableWords.draw();
    tableWordsScrollYElement.scrollTop = tableWordsLastScrollPosition;
}

function initEventHandlersTableWords() {
    $('.iconSmallButtonDataTablesHeader').mouseover(function () {
        $(this).parent().css('background', '#178ACC');
    });

    $('.iconSmallButtonDataTablesHeader').mouseout(function () {
        $(this).parent().css('background', 'white');
    });

    $('.iconSmallButtonDataTablesHeader').click(function () {
        var groupName = $(this).parent().parent().text().toString().trim();
        mapGroupWords[groupName] = (mapGroupWords[groupName] === true) ? false : true;

        tableWords.draw();
        initEventHandlersTableWords();
    });

    $('#tableWords tbody a[word_of_card]').on('click', function () {
        showWordCard($(this).attr('word_of_card'));
        return false;
    });

    $('#tableWords tbody a[word_of_forvo_card]').on('click', function () {
        showForvoWordCard($(this).attr('word_of_forvo_card'));
        return false;
    });

    $('#tableWords tbody a[word_of_mnemonic_card]').on('click', function () {
        showMnemonicWordCard($(this).attr('word_of_mnemonic_card'));
        return false;
    });
}


/****************************************************************************************************************************************************************************************************************************************/
/*                                                                                                                      Word card                                                                                                       */
/****************************************************************************************************************************************************************************************************************************************/
var wordNameActiveCard = "";

var globalWindow;

function showWordCard(word) {
    globalWindow = window;

    var wordCardWindow = $("#wordCardWindow");
    wordCardWindow.find(".modal-title").text(word);
    document.getElementById("wordCard_first_part").innerHTML = "<iframe src='/wordCard/loadFromEnRuDictionaries.html?word=" + word + "' wordCardBaseURL='/wordCard/loadFromEnRuDictionaries.html?word=' scrolling='hidden' frameborder='1' style='background-color: white;'>" +
        "    Your agent does not support frames or is configured not to display them." +
        "</iframe>";
    wordNameActiveCard = word;
    showMainDictionaries();
    wordCardWindow.modal("show");
    return false;
}

function showMainDictionaries() {
    document.getElementById("wordCard_second_part").innerHTML = "<iframe id='mainOALD9' src='/wordCard/loadFromOALD9.html?word=" + wordNameActiveCard + "&needToShowKeyWords=" + needToShowKeyWords + "' wordCardBaseURL='/wordCard/loadFromOALD9.html?word=' scrolling='hidden' frameborder='1' style='background-color: white;'>" +
        "    Your agent does not support frames or is configured not to display them." +
        "</iframe>";
    redrawRightWordCard = showMainDictionaries;
}

function showLDOCE6Dictionary() {
    document.getElementById("wordCard_second_part").innerHTML = "<iframe id='mainLDOCE6' src='/wordCard/loadFromLDOCE6.html?word=" + wordNameActiveCard + "&needToShowKeyWords=" + needToShowKeyWords + "' wordCardBaseURL='/wordCard/loadFromLDOCE6.html?word=' scrolling='hidden' frameborder='1' style='background-color: white;'>" +
        "    Your agent does not support frames or is configured not to display them." +
        "</iframe>";
    redrawRightWordCard = showLDOCE6Dictionary;
}

function showADLA2Dictionary() {
    document.getElementById("wordCard_second_part").innerHTML = "<iframe id='mainADLA2' src='/wordCard/loadFromADLA2.html?word=" + wordNameActiveCard + "&needToShowKeyWords=" + needToShowKeyWords + "' wordCardBaseURL='/wordCard/loadFromADLA2.html?word=' scrolling='hidden' frameborder='1' style='background-color: white;'>" +
        "    Your agent does not support frames or is configured not to display them." +
        "</iframe>";
    redrawRightWordCard = showADLA2Dictionary;
}

function showCollocationsDictionaries() {
    document.getElementById("wordCard_second_part").innerHTML = "<iframe src='/wordCard/loadFromCollocations.html?word=" + wordNameActiveCard + "' onFocus=\"setWordCardBaseURL('/wordCard/loadFromCollocations.html?word=')\" scrolling='hidden' frameborder='1' style='background-color: white;'>" +
        "    Your agent does not support frames or is configured not to display them." +
        "</iframe>";
    redrawRightWordCard = showCollocationsDictionaries;
}

function showThesaurusDictionaries() {
    document.getElementById("wordCard_second_part").innerHTML = "<iframe id='mainThesaurus' src='/wordCard/loadFromThesaurus.html?word=" + wordNameActiveCard + "&needToShowKeyWords=" + needToShowKeyWords + "' wordCardBaseURL='/wordCard/loadFromThesaurus.html?word=' onFocus=\"setWordCardBaseURL('/loadWordCardFromThesaurus.html?word=')\" scrolling='hidden' frameborder='1' style='background-color: white;'>" +
        "    Your agent does not support frames or is configured not to display them." +
        "</iframe>";
    redrawRightWordCard = showThesaurusDictionaries;
}


/****************************************************************************************************************************************************************************************************************************************/
/*                                                                                                                      Quizlet                                                                                                         */
/****************************************************************************************************************************************************************************************************************************************/
function showQuizletFrame() {
    if (selectContentFileWords.selectedIndex == 0) {
        showWarningMessage("Quizlet", "Need to choose set of words", false);
    } else {
        httpGetAsync('/quizlet/isAutorizated.html', null, showQuizletFrameCallback);
    }
}

function showQuizletFrameCallback(responseText) {
    var jsonResponse = JSON.parse(responseText);
    if (jsonResponse.isQuizletAutorizated === true) {
        $('#tableWords_wrapper').hide();
        quizletDeleteAllSets( function() {
            quizletExportThisSet( function(responseText1) {
                var jsonResponse = JSON.parse(responseText1);
                // TODO
                document.getElementById('quizlet_iframe').innerHTML = "<iframe src=\"https://quizlet.com/" + jsonResponse.setId + "/match/embed\" width=\"99%\" style=\"border:0;position: absolute;top: 86px;\"></iframe>";
                $('#quizlet_iframe').show();
            });
        });
    } else {
        quizletLogin();
    }
}

function httpGetAsync(theUrl, successCallback, externalCallback) {
    var xmlHttp = new XMLHttpRequest();
    xmlHttp.onreadystatechange = function() {
        if (xmlHttp.readyState == 4 && xmlHttp.status == 200) {
            if (successCallback != null) {
                successCallback(xmlHttp.responseText);
            }

            if (externalCallback != null) {
                externalCallback(xmlHttp.responseText);
            }
        }
    };
    xmlHttp.open("GET", theUrl, true); // true for asynchronous
    xmlHttp.send(null);
}

function quizletLogin() {
    $.get('/quizlet/authenticationURL.html', authenticationOnQuizlet)
        .error(function (jqXHR, textStatus, errorThrown) {
            showErrorMessage("Quizlet", "There was a server error when requesting authorization URL'a Quizlet: (" + jqXHR.status + ") " + errorThrown, true);
        });
}

function authenticationOnQuizlet(response) {
    if (response.authenticationURL != null) {
        window.location.href = response.authenticationURL;
    }
}

function quizletDeleteAllSets(callback) {
    httpGetAsync('/quizlet/deleteAllSets.html', null, callback);
}

function quizletExportThisSet(callback) {
    var setName = selectContentFileWords.options[selectContentFileWords.selectedIndex].key;
    var setFileName = selectContentFileWords.options[selectContentFileWords.selectedIndex].value;
    httpGetAsync('/quizlet/exportSet.html?setName=' + setName + '&fileName=' + setFileName, null, callback);
}


/****************************************************************************************************************************************************************************************************************************************/
/*                                                                                                                   LinguaLeo                                                                                                          */
/****************************************************************************************************************************************************************************************************************************************/
function exportThisSetToLingualeo() {
    if (selectContentFileWords.selectedIndex == 0) {
        showWarningMessage("Lingualeo", "Need to choose set of words", false);
    } else {
        var setFileName = selectContentFileWords.options[selectContentFileWords.selectedIndex].value;
        $.get('/lingualeo/exportCurrentSet.html?fileName=' + setFileName, handleExportThisSetToLingualeo)
            .error(function (jqXHR, textStatus, errorThrown) {
                showErrorMessage("LinguaLeo", "There was a server error occurred while export to LinguaLeo: (" + jqXHR.status + ") " + errorThrown, true);
            });
    }
}

function handleExportThisSetToLingualeo(responce) {
    showSuccessMessage("LinguaLeo", "Export of list word to LinguaLeo is complete", false);
}


/****************************************************************************************************************************************************************************************************************************************/
/*                                                                                                                      Forvo                                                                                                           */
/****************************************************************************************************************************************************************************************************************************************/
function showForvoWordCard(word) {
    var wordCardWindow = $("#wordForvoCardWindow");
    wordCardWindow.find(".modal-title").text(word);
    document.getElementById("wordForvoCard_articles").innerHTML = "<iframe src='/forvoVoicesCard/show.html?word=" + word + "' width='100%' height='617px' scrolling='auto' frameborder='1' style='background-color: white'>" +
        "    Your agent does not support frames or is configured not to display them." +
        "</iframe>";
    wordCardWindow.modal("show");
    return false; // TODO: Read about it
}


/****************************************************************************************************************************************************************************************************************************************/
/*                                                                                                                      Mnemonics                                                                                                       */
/****************************************************************************************************************************************************************************************************************************************/
function showMnemonicWordCard(word) {
    var wordCardWindow = $("#wordMnemonicWindow");
    wordCardWindow.find(".modal-title").text(word);
    document.getElementById("wordMnemonic_articles").innerHTML = "<iframe src='/mnemonicCard/show.html?word=" + word + "' width='100%' height='617px' scrolling='auto' frameborder='1' style='background-color: white'>" +
        "    Your agent does not support frames or is configured not to display them." +
        "</iframe>";
    wordCardWindow.modal("show");
    return false; // TODO: Read about it
}


/****************************************************************************************************************************************************************************************************************************************/
/*                                                                                                                      Comments                                                                                                        */
/****************************************************************************************************************************************************************************************************************************************/
function showComments(response) {
    if (response.haseComments === true) {
        document.getElementById("containerComments").innerHTML =
            "<iframe src='" + response.commentsFileName + "' width='100%' height='617px' scrolling='auto' frameborder='1' style='background-color: white'>" +
            "    Your agent does not support frames or is configured not to display them." +
            "</iframe>";
        tableWords.buttons(buttonCommentsIndex).enable(false);
        $('#formComments').removeClass("hide");
    } else {
        showNoticeMessage("Comments", response.noCommentsMessage, false);
    }
    return false;
}


/****************************************************************************************************************************************************************************************************************************************/
/*                                                                                                                  Hot keys                                                                                                            */
/****************************************************************************************************************************************************************************************************************************************/
function registerHotKeys() {
    window.npup = (function keypressListener() {
        // Object to hold keyCode/handler mappings
        var mappings = {};
        // Default options for additional meta keys
        var defaultOptions = {ctrl:false, alt:false};
        // Flag for if we're running checks or not
        var active = false;

        // The function that gets called on keyup.
        // Tries to find a handler to execute
        function driver(event) {
            var keyCode = event.keyCode, ctrl = !!event.ctrlKey, alt = !!event.altKey;
            var key = buildKey(keyCode, ctrl, alt);
            var handler = mappings[key];
            if (handler) {handler(event);}
        }

        // Take the three props and make a string to use as key in the hash
        function buildKey(keyCode, ctrl, alt) {return (keyCode+'_'+ctrl+'_'+alt);}

        function listen(keyCode, handler, options) {
            // Build default options if there are none submitted
            options = options || defaultOptions;
            if (typeof handler!=='function') {throw new Error('Submit a handler for keyCode #'+keyCode+'(ctrl:'+!!options.ctrl+', alt:'+options.alt+')');}
            // Build a key and map handler for the key combination
            var key = buildKey(keyCode, !!options.ctrl, !!options.alt);
            mappings[key] = handler;
        }

        function unListen(keyCode, options) {
            // Build default options if there are none submitted
            options = options || defaultOptions;
            // Build a key and map handler for the key combination
            var key = buildKey(keyCode, !!options.ctrl, !!options.alt);
            // Delete what was found
            delete mappings[key];
        }

        // Rudimentary attempt att cross-browser-ness
        var xb = {
            addEventListener: function (element, eventName, handler) {
                if (element.attachEvent) {element.attachEvent('on'+eventName, handler);}
                else {element.addEventListener(eventName, handler, false);}
            },
            removeEventListener: function (element, eventName, handler) {
                if (element.attachEvent) {element.detachEvent('on'+eventName, handler);}
                else {element.removeEventListener(eventName, handler, false);}
            }
        };

        function setActive(activate) {
            activate = (typeof activate==='undefined' || !!activate); // true is default
            if (activate===active) {return;} // already in the desired state, do nothing
            var addOrRemove = activate ? 'addEventListener' : 'removeEventListener';
            xb[addOrRemove](document, 'keyup', driver);
            active = activate;
        }

        // Activate on load
        setActive();

        // export API
        return {
            // Add/replace handler for a keycode.
            // Submit keycode, handler function and an optional hash with booleans for properties 'ctrl' and 'alt'
            listen: listen,
            // Remove handler for a keycode
            // Submit keycode and an optional hash with booleans for properties 'ctrl' and 'alt'
            unListen: unListen,
            // Turn on or off the whole thing.
            // Submit a boolean. No arg means true
            setActive: setActive,
            // Keycode constants, fill in your own here
            key : {
                VK_X: 88,
                VK_Z: 90
            }
            // Tables of key codes: http://umi-cms.spb.su/ref/javascript/251/
        };
    })();

    npup.listen(npup.key.VK_Z, function (event) {
        needToShowKeyWords = true;
        if (redrawRightWordCard != null) {
            redrawRightWordCard();
        }
    }, {ctrl: true});

    npup.listen(npup.key.VK_X, function (event) {
        needToShowKeyWords = false;
        if (redrawRightWordCard != null) {
            redrawRightWordCard();
        }
    }, {ctrl: true});

    // Small demo of listen and unListen
    // Usage:
    //  listen(key, handler [,options])
    //  unListen(key, [,options])
    /*npup.listen(npup.key.VK_F1, function (event) {
        console.log('F1, adding listener on \'B\'');
        npup.listen(npup.key.VK_B, function (event) {
            console.log('B');
        });
    });
    npup.listen(npup.key.VK_F2, function (event) {
        console.log('F2, removing listener on \'B\'');
        npup.unListen(npup.key.VK_B);
    });
    npup.listen(npup.key.VK_A, function (event) {
        console.log('ctrl-A');
    }, {ctrl: true});
    npup.listen(npup.key.VK_A, function (event) {
        console.log('ctrl-alt-A');
    }, {ctrl: true, alt: true});
    npup.listen(npup.key.VK_C, function (event) {
        console.log('ctrl-alt-C => It all ends!');
        npup.setActive(false);
    }, {ctrl: true, alt: true});*/
}


/****************************************************************************************************************************************************************************************************************************************/
/*                                                                                                                   Alerts                                                                                                             */
/****************************************************************************************************************************************************************************************************************************************/
//about:
//http://techndj.blogspot.ru/2015/11/jquery-toastmessage-plugin-make-it-easy.html
function showSuccessMessage(title, message, sticky) {
    showToastMessage(title, message, sticky, 'success');
}

function showNoticeMessage(title, message, sticky) {
    showToastMessage(title, message, sticky, 'notice');
}

function showWarningMessage(title, message, sticky) {
    showToastMessage(title, message, sticky, 'warning');
}

function showErrorMessage(title, message, sticky) {
    showToastMessage(title, message, sticky, 'error');
}

function showToastMessage(title, message, sticky, messageType) {
    var options = {text: '<b>' + title + '</b><br>' + message, sticky: sticky, position: 'center', type: messageType};
    $().toastmessage('showToast', options);
}


/****************************************************************************************************************************************************************************************************************************************/
/*                                                                                                                      Utils                                                                                                           */
/****************************************************************************************************************************************************************************************************************************************/
function isEmptyString(str) {
    return (!str || 0 === str.length);
}
