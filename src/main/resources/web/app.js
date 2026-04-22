const liveClock = document.getElementById("liveClock");
const lastFertilized = document.getElementById("lastFertilized");
const lastWatered = document.getElementById("lastWatered");
const fertilizedBtn = document.getElementById("fertilizedBtn");
const wateredBtn = document.getElementById("wateredBtn");
const feuchtigkeit = document.getElementById("feuchtigkeit");
const progressFill = document.getElementById("progressFill");
const statusText = document.getElementById("statusText");

function updateClock() {
    const now = new Date();
    liveClock.textContent = now.toLocaleTimeString("de-DE");
}

function updateMoisture(value) {
    feuchtigkeit.textContent = value + " %";
    progressFill.style.width = value + "%";

    if (value < 30) {
        statusText.textContent = "Zu trocken";
    } else if (value < 70) {
        statusText.textContent = "Optimal";
    } else {
        statusText.textContent = "Sehr feucht";
    }
}

async function fetchStatus() {
    try {
        const response = await fetch("/api/status");
        const data = await response.json();

        if (data.wert === null) {
            feuchtigkeit.textContent = "Noch keine Daten";
            progressFill.style.width = "0%";
            statusText.textContent = "Warte auf Sensordaten";
            return;
        }

        updateMoisture(data.wert);
    } catch (error) {
        console.error("Fehler beim Laden des Status:", error);
        statusText.textContent = "Verbindung fehlgeschlagen";
    }
}

async function loadActions() {
    try {
        const response = await fetch("/api/actions");
        const data = await response.json();

        if (data.letzterGiessZeitpunkt) {
            lastWatered.textContent = data.letzterGiessZeitpunkt;
        } else {
            lastWatered.textContent = "Noch nicht gespeichert";
        }

        if (data.letzterDuengeZeitpunkt) {
            lastFertilized.textContent = data.letzterDuengeZeitpunkt;
        } else {
            lastFertilized.textContent = "Noch nicht gespeichert";
        }
    } catch (error) {
        console.error("Fehler beim Laden der gespeicherten Aktionen:", error);
        lastWatered.textContent = "Fehler beim Laden";
        lastFertilized.textContent = "Fehler beim Laden";
    }
}

async function saveWatered() {
    try {
        const response = await fetch("/api/giessen", {
            method: "POST"
        });

        const data = await response.json();

        if (data.status === "ok") {
            lastWatered.textContent = data.zeitpunkt;
        } else {
            lastWatered.textContent = "Speichern fehlgeschlagen";
        }
    } catch (error) {
        console.error("Fehler beim Speichern von 'gegossen':", error);
        lastWatered.textContent = "Speichern fehlgeschlagen";
    }
}

async function saveFertilized() {
    try {
        const response = await fetch("/api/duengen", {
            method: "POST"
        });

        const data = await response.json();

        if (data.status === "ok") {
            lastFertilized.textContent = data.zeitpunkt;
        } else {
            lastFertilized.textContent = "Speichern fehlgeschlagen";
        }
    } catch (error) {
        console.error("Fehler beim Speichern von 'gedüngt':", error);
        lastFertilized.textContent = "Speichern fehlgeschlagen";
    }
}

fertilizedBtn.addEventListener("click", saveFertilized);
wateredBtn.addEventListener("click", saveWatered);

setInterval(updateClock, 1000);
updateClock();

setInterval(fetchStatus, 1000);
fetchStatus();

loadActions();