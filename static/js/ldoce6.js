function toggle(expandable) {
    var expParent = expandable.parentNode;
    var target = expParent.querySelector('.content');
    if (target !== null) {
        if (target.addEventListener) {
            target.addEventListener('click', function (event) {
                event.stopPropagation();
            });
        } else {
            target.attachEvent('onclick', function (event) {
                event.cancelBubble = false;
            });
        }
    }
    var arrow = expParent.querySelector('span.arrow');
    if (arrow !== null) {
        if (target.style.display == 'block') {
            target.style.display = 'none';
            arrow.innerHTML = '\u25BA';
        } else {
            target.style.display = 'block';
            arrow.innerHTML = '\u25BC';
        }
    }
}

function showAtLink(ele) {
    if (ele.nextElementSibling.style.display == 'block') {
        ele.nextElementSibling.style.display = 'none';
    } else {
        ele.nextElementSibling.style.display = 'block';
    }
}

function toggleImg(ele) {
    if (ele.style.maxHeight == 'none') {
        ele.style.maxHeight = '4em';
    } else {
        ele.style.maxHeight = 'none';
    }
}

