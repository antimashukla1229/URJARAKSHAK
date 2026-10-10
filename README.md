# ⚡ UrjaRakshak (Urja-Rakshak)
> **AI-Powered Grid Intelligence, Thermal Twin, & Revenue Loss Monitoring System**

UrjaRakshak is an end-to-end smart grid management dashboard designed for Indian DISCOMs. It monitors distribution transformer health, detects non-technical losses (electricity theft/meter tampering), models thermal stress, and provides field inspection routing along with an embedded AI assistant (**ERIC**).

---

## 🚀 Key Features

* **⚡ Real-Time Grid Telemetry & Map:** Interactive schematic tracking 8 distribution transformers (`TR-101` to `TR-108`) with capacities ranging from 63kVA to 200kVA.
* **🔥 Thermal Twin & Hotspot Analysis:** Tracks operating temperatures against the 110°C thermal limit and calculates cumulative stress hours.
* **🕵️ Non-Technical Loss Detection:** Flagged consumer meters based on peer consumption trend divergence (theft probability algorithms).
* **📋 Ranked Inspection Routing:** Priority dispatch table ranking targets by expected monthly recovery (₹) vs. inspection cost.
* **👤 Role-Based Profiles:** Tailored operational metadata for **DISCOM Officers** (Executive Engineers) and **Field Inspectors** (Line Staff).
* **🏡 Consumer Portal (`consumer.html`):** Allows consumers to lodge power theft complaints and apply for vacation-mode meter audits.
* **🤖 ERIC Grid Assistant:** Self-injecting floating chatbot providing fast telemetry lookups and inspection guidance.

---

## 🏗️ System Architecture & Tech Stack

```text
       [ ETTh1 Dataset / Data Source ]
                    │
                    ▼
          [ Python ETL Pipeline ]
        (Normalization & Resampling)
                    │
                    ▼
  [ Java Spring Boot Backend (REST API) ]
  ├── AuthController & DataStore (Persistence)
  ├── GridController / ThermalController
  └── InspectionController / ProfileController
                    │
                    ▼
   [ Vanilla JS / CSS / HTML Frontend ]
   (index.html, health.html, consumer.html, shared.js)
