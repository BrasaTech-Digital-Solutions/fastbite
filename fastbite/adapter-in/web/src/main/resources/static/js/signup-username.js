(() => {
    const input = document.getElementById("username"), status = document.getElementById("username-status");
    if (!input || !status) return;
    let timer, sequence = 0;
    input.addEventListener("input", () => {
        clearTimeout(timer); const current = ++sequence;
        status.textContent = "";
        if (!input.value || !input.validity.valid) return;
        timer = setTimeout(async () => {
            try {
                const response = await fetch("/signup/username-available?username=" + encodeURIComponent(input.value));
                if (!response.ok) return;
                const available = await response.json();
                if (current === sequence) status.textContent = available ? status.dataset.available : status.dataset.unavailable;
            } catch (_) { /* The final signup validates availability as well. */ }
        }, 300);
    });
})();
