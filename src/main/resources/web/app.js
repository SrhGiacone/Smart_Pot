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

function getFormattedDateTime() {
    const now = new Date();
    return now.toLocaleString("de-DE");
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

fertilizedBtn.addEventListener("click", () => {
    lastFertilized.textContent = getFormattedDateTime();
});

wateredBtn.addEventListener("click", () => {
    lastWatered.textContent = getFormattedDateTime();
});

setInterval(updateClock, 1000);
updateClock();

setInterval(fetchStatus, 1000);
fetchStatus();