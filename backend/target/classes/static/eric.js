/* =========================================================
   UrjaRakshak - ERIC Grid Assistant (Self-Injecting)
   ========================================================= */

(function () {
    function init() {
        if (document.getElementById("eric-chat-container")) return;
        if (!document.body) {
            setTimeout(init, 100);
            return;
        }
        injectEricUI();
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", init);
    } else {
        init();
    }
})();

function injectEricUI() {
    if (document.getElementById("eric-chat-container")) return;

    const ericHTML = `
        <div id="eric-chat-container" style="position: fixed; bottom: 20px; right: 20px; z-index: 99999; font-family: sans-serif;">
            <button id="eric-toggle-btn" onclick="toggleEricChat()" style="width: 60px; height: 60px; border-radius: 50%; background: #007bff; border: none; color: white; font-size: 28px; cursor: pointer; box-shadow: 0 4px 12px rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center;">
                🤖
            </button>

            <div id="eric-chat-window" style="display: none; width: 340px; height: 450px; background: white; border-radius: 12px; box-shadow: 0 5px 20px rgba(0,0,0,0.3); position: absolute; bottom: 70px; right: 0; flex-direction: column; overflow: hidden; border: 1px solid #ddd;">
                <div style="background: #007bff; color: white; padding: 12px 16px; font-weight: bold; display: flex; justify-content: space-between; align-items: center;">
                    <span>🤖 ERIC Grid Assistant</span>
                    <button onclick="toggleEricChat()" style="background: none; border: none; color: white; font-size: 18px; cursor: pointer;">✕</button>
                </div>

                <div id="eric-messages" style="flex: 1; padding: 12px; overflow-y: auto; background: #f9f9f9; display: flex; flex-direction: column; gap: 8px;">
                    <div style="background: #e9ecef; color: #333; padding: 8px 12px; border-radius: 8px; max-width: 80%; align-self: flex-start; font-size: 14px;">
                        Hello! I am ERIC. Ask me about grid telemetry, transformer status, or DISCOM regulations!
                    </div>
                </div>

                <div style="padding: 10px; border-top: 1px solid #ddd; display: flex; gap: 6px; background: white;">
                    <input type="text" id="eric-input" placeholder="Ask ERIC..." onkeypress="handleEricKeyPress(event)" style="flex: 1; padding: 8px; border: 1px solid #ccc; border-radius: 6px; outline: none; font-size: 14px;">
                    <button onclick="sendEricMessage()" style="background: #007bff; color: white; border: none; padding: 8px 12px; border-radius: 6px; cursor: pointer;">Send</button>
                </div>
            </div>
        </div>
    `;

    document.body.insertAdjacentHTML("beforeend", ericHTML);
}

function toggleEricChat() {
    const win = document.getElementById("eric-chat-window");
    if (win) {
        win.style.display = (win.style.display === "none" || win.style.display === "") ? "flex" : "none";
    }
}

function handleEricKeyPress(event) {
    if (event.key === "Enter") {
        sendEricMessage();
    }
}

async function sendEricMessage() {
    const input = document.getElementById("eric-input");
    const msgContainer = document.getElementById("eric-messages");
    const userQuery = input.value.trim();

    if (!userQuery) return;

    const userBubble = document.createElement("div");
    userBubble.style.cssText = "background: #007bff; color: white; padding: 8px 12px; border-radius: 8px; max-width: 80%; align-self: flex-end; font-size: 14px;";
    userBubble.innerText = userQuery;
    msgContainer.appendChild(userBubble);

    input.value = "";
    msgContainer.scrollTop = msgContainer.scrollHeight;

    const botBubble = document.createElement("div");
    botBubble.style.cssText = "background: #e9ecef; color: #333; padding: 8px 12px; border-radius: 8px; max-width: 85%; align-self: flex-start; font-size: 14px;";
    botBubble.innerText = "Thinking...";
    msgContainer.appendChild(botBubble);
    msgContainer.scrollTop = msgContainer.scrollHeight;

    const responseText = await getEricResponse(userQuery);
    botBubble.innerHTML = responseText.replace(/\n/g, "<br>");
    msgContainer.scrollTop = msgContainer.scrollHeight;
}

async function getEricResponse(userQuery) {
    if (!userQuery || !userQuery.trim()) return "Please type a question!";
    
    const q = userQuery.toLowerCase().trim();

    // 1. Basic Greetings
    if (q === "hi" || q === "hello" || q === "hey" || q === "hi eric") {
        return "Hello! How can I assist you with grid intelligence or DISCOM analysis today?";
    }

    // 2. Transformer Check (TR-101 to TR-108)
    const trMatch = q.match(/tr[-_ ]?10[1-8]/i);
    if (trMatch || (q.includes("transformer") && !q.includes("rule"))) {
        try {
            const res = await fetch("/api/transformers");
            if (res.ok) {
                const transformers = await res.json();
                let trId = trMatch ? trMatch[0].toUpperCase().replace(" ", "-").replace("_", "-") : null;
                if (trId && !trId.includes("-")) {
                    trId = trId.slice(0, 2) + "-" + trId.slice(2);
                }

                const tr = transformers.find(t => t.id === trId);
                if (tr) {
                    return `⚡ **Transformer ${tr.id} Report:**\n\n` +
                           `• **Capacity:** ${tr.capacityKva} kVA\n` +
                           `• **Peak Load:** ${tr.peakLoadPct}%\n` +
                           `• **Unexplained Loss:** ${tr.unexplainedPct}%\n` +
                           `• **Unexplained Energy:** ${tr.unexplainedKwh} kWh`;
                }
            }
        } catch (e) {
            console.error("Local API error:", e);
        }
    }

    // 3. Inspection Priority Queries
    if (q.includes("inspect") || q.includes("priority") || q.includes("first") || q.includes("risk")) {
        try {
            const res = await fetch("/api/inspections");
            if (res.ok) {
                const data = await res.json();
                if (data && data.length > 0) {
                    const top = data[0];
                    return `🔎 **Priority Inspection Target:**\n\n` +
                           `• **Target:** ${top.target} (${top.transformer})\n` +
                           `• **Reason:** ${top.reason}\n` +
                           `• **Est. Recovery:** ₹${top.estMonthlyRecoveryInr}\n` +
                           `• **Cost:** ₹${top.costInr}`;
                }
            }
        } catch (e) {
            console.error("Local Inspection API error:", e);
        }
    }

    // 4. Strict TinyFish Fallback for External Queries
    try {
        const response = await fetch("/api/eric/web-query", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ query: userQuery })
        });

        if (!response.ok) {
            return "⚠️ TinyFish endpoint unreachable. Ensure Spring Boot backend is running.";
        }

        const data = await response.json();

        if (data.status === "FAILED") {
            return `🤖 TinyFish Error: ${data.error}`;
        } else if (data.result) {
            const resultText = typeof data.result === "string" ? data.result : JSON.stringify(data.result);
            return `🌐 **TinyFish Web Intelligence:**\n${resultText}`;
        } else {
            return `🤖 TinyFish processed the request, but returned no result.`;
        }

    } catch (err) {
        console.error("TinyFish Request Exception:", err);
        return "⚠️ Unable to reach Spring Boot backend server.";
    }
}
