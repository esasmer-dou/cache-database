package com.reactor.cachedb.examples.demo;

import com.reactor.cachedb.examples.demo.entity.DemoCartEntity;
import com.reactor.cachedb.examples.demo.entity.DemoCustomerEntity;
import com.reactor.cachedb.examples.demo.entity.DemoOrderEntity;
import com.reactor.cachedb.examples.demo.entity.DemoOrderLineEntity;
import com.reactor.cachedb.examples.demo.entity.DemoProductEntity;

import java.util.List;
import java.util.Objects;

public final class DemoScenarioUiSupport {

    private DemoScenarioUiSupport() {
    }

    public static DemoScenarioLevel parseLevel(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return DemoScenarioLevel.LOW;
        }
        for (String part : rawQuery.split("&")) {
            String[] tokens = part.split("=", 2);
            if (tokens.length == 2 && "level".equalsIgnoreCase(tokens[0])) {
                return DemoScenarioLevel.valueOf(tokens[1].trim().toUpperCase());
            }
        }
        return DemoScenarioLevel.LOW;
    }

    public static String renderActionAck(DemoScenarioActionStateSnapshot actionState, String instanceId) {
        return "{\"accepted\":true,\"instanceId\":\"" + escapeJson(instanceId) + "\",\"action\":" + renderActionState(actionState, instanceId) + "}";
    }

    public static String renderActionStateOnly(DemoScenarioActionStateSnapshot actionState, String instanceId) {
        return renderActionState(actionState, instanceId);
    }

    public static String renderStatus(DemoScenarioService service, DemoScenarioActionStateSnapshot actionState, String instanceId) {
        DemoScenarioSnapshot snapshot = service.snapshot();
        return "{\"instanceId\":\"" + escapeJson(instanceId) + "\",\"snapshot\":" + renderSnapshot(snapshot, service)
                + ",\"action\":" + renderActionState(actionState, instanceId)
                + "}";
    }

    public static String renderViews(DemoScenarioService service) {
        return "{\"customers\":" + renderCustomers(service.customersView())
                + ",\"products\":" + renderProducts(service.productsView())
                + ",\"carts\":" + renderCarts(service.cartsView())
                + ",\"orders\":" + renderOrders(service.ordersView())
                + ",\"orderLines\":" + renderOrderLines(service.orderLinesView())
                + "}";
    }

    public static String renderScenarioShapes(DemoScenarioShapeSnapshot snapshot) {
        StringBuilder builder = new StringBuilder("{\"recordedAtEpochMillis\":")
                .append(snapshot.recordedAtEpochMillis())
                .append(",\"items\":[");
        List<DemoScenarioShapeRecord> items = snapshot.items();
        for (int index = 0; index < items.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            DemoScenarioShapeRecord item = items.get(index);
            builder.append("{\"key\":\"").append(escapeJson(item.key())).append("\",")
                    .append("\"mode\":\"").append(escapeJson(item.mode())).append("\",")
                    .append("\"sampleCount\":").append(item.sampleCount()).append(',')
                    .append("\"lastPrimaryCount\":").append(item.lastPrimaryCount()).append(',')
                    .append("\"averagePrimaryCount\":").append(item.averagePrimaryCount()).append(',')
                    .append("\"lastRelatedCount\":").append(item.lastRelatedCount()).append(',')
                    .append("\"averageRelatedCount\":").append(item.averageRelatedCount()).append(',')
                    .append("\"lastStepCount\":").append(item.lastStepCount()).append(',')
                    .append("\"averageStepCount\":").append(item.averageStepCount()).append(',')
                    .append("\"lastObjectCount\":").append(item.lastObjectCount()).append(',')
                    .append("\"averageObjectCount\":").append(item.averageObjectCount()).append(',')
                    .append("\"lastWriteCount\":").append(item.lastWriteCount()).append(',')
                    .append("\"averageWriteCount\":").append(item.averageWriteCount()).append(',')
                    .append("\"lastRecordedAtEpochMillis\":").append(item.lastRecordedAtEpochMillis())
                    .append('}');
        }
        builder.append("]}");
        return builder.toString();
    }

    public static String renderPage(DemoScenarioTuning tuning, String adminUrl, String apiBasePath, String instanceId) {
        String basePath = normalizeApiBasePath(apiBasePath);
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>CacheDB Demo Load UI</title>"
                + "<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\">"
                + "<link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin>"
                + "<link href=\"https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=IBM+Plex+Mono:wght@400;500&display=swap\" rel=\"stylesheet\">"
                + "<link href=\"https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css\" rel=\"stylesheet\">"
                + "<style>"
                + "body{background:linear-gradient(180deg,#f6f4ef 0%,#efe9de 100%);color:#1b2733;font-family:'Inter','Segoe UI',system-ui,sans-serif;line-height:1.55;letter-spacing:-.01em}"
                + "main{max-width:1560px;margin:0 auto}.card{border:1px solid rgba(148,163,184,.18);box-shadow:0 .4rem 1rem rgba(15,23,42,.06);border-radius:1rem;background:rgba(255,255,255,.9)}"
                + ".card-header{background:rgba(255,255,255,.88);border-bottom:1px solid #e8edf3;font-weight:700;padding:1rem 1.1rem}.card-body{padding:1rem 1.1rem 1.1rem}"
                + ".metric{font-size:1.55rem;font-weight:700;line-height:1.15;color:#102a43;margin-top:.35rem}.mono{font-family:'IBM Plex Mono','JetBrains Mono',Consolas,monospace}"
                + ".action-btn{border-radius:.85rem;font-weight:600;letter-spacing:-.01em;padding:.72rem 1rem}.action-btn.active-level{box-shadow:0 0 0 .25rem rgba(13,110,253,.18);transform:translateY(-1px)}"
                + ".action-btn.request-busy{opacity:.78}.helper{font-size:.94rem;color:#52606d;line-height:1.55}.section-note{font-size:.92rem;color:#52606d;line-height:1.5}"
                + ".sticky-top-lite{position:sticky;top:1rem;z-index:5}.table-responsive{border:1px solid rgba(226,232,240,.9);border-radius:.95rem;background:#fff}"
                + ".table thead th{font-size:.76rem;text-transform:uppercase;letter-spacing:.07em;color:#6b7a89;background:#f8fafc;border-bottom:1px solid #e8edf3;position:sticky;top:0}.table tbody tr:hover{background:rgba(248,250,252,.88)}"
                + ".action-state{font-size:.87rem;color:#52606d}.queue-badge{font-size:.78rem;padding:.38rem .55rem}.hero-title{font-size:2rem;font-weight:700;letter-spacing:-.03em;margin-bottom:.2rem}"
                + ".hero-subtitle{color:#5b6774;max-width:68ch}.soft-panel{padding:.9rem 1rem;border-radius:.95rem;background:#f8fafc;border:1px solid #e6edf5}.metric-strip .card-body{padding:.95rem 1rem}"
                + "</style></head><body><main class=\"container-fluid px-4 py-4\">"
                + "<div class=\"d-flex justify-content-between align-items-start gap-3 flex-wrap mb-4\"><div><h1 class=\"hero-title\">Load Scenario Workspace</h1><div class=\"hero-subtitle\">Örnek veriyi yükleyip düşük, orta ve yüksek yük profilleri arasında geçiş yaparak hem toplu okuma hem de toplu yazma baskısını admin konsolundaki sinyaller üzerinden canlı izleyebilirsin.</div></div>"
                + "<a class=\"btn btn-outline-dark action-btn\" href=\"" + escapeHtml(adminUrl) + "\" target=\"_blank\">Open Admin Dashboard</a></div>"
                + "<div class=\"card mb-3\"><div class=\"card-body\"><div class=\"row g-3 align-items-start\">"
                + "<div class=\"col-12 col-xl-5\"><div class=\"soft-panel h-100\"><div class=\"fw-semibold mb-2\">Bu çalışma alanı ne için?</div><div class=\"helper\">Önce <span class=\"mono\">Seed Demo Data</span> ile makul hacimde örnek kayıtları yüklersin. Sonra <span class=\"mono\">Low</span>, <span class=\"mono\">Medium</span> veya <span class=\"mono\">High</span> ile arka planda sadece küçük tekil okumalar değil, tum musteri taramasi, buyuk katalog penceresi ve cok satirli siparis okumalari da uretilir. Yazma tarafinda toplu stok, sepet ve siparis guncellemeleri akar.</div></div></div>"
                + "<div class=\"col-12 col-xl-4\"><div class=\"soft-panel h-100\"><div class=\"fw-semibold mb-2\">Seed verisi</div><div id=\"seedSummary\" class=\"helper\">" + escapeHtml(tuning.seedSummary()) + "</div><div class=\"small text-muted mt-2\">Start butonlari gizlice seed baslatmaz. Veri hazir degilse once <span class=\"mono\">Seed Demo Data</span> calistir. Sadece demo kayıtlarını silmek için <span class=\"mono\">Clear Data</span>, Redis + PostgreSQL + telemetriyi temizlemek için <span class=\"mono\">Fresh Start</span> kullanılır.</div></div></div>"
                + "<div class=\"col-12 col-xl-3\"><div class=\"soft-panel h-100\"><div class=\"fw-semibold mb-2\">Şu anki durum</div><div id=\"currentAction\" class=\"helper\">Yükleniyor...</div><div class=\"small text-muted mt-2\">Sunucu oturumu: <span id=\"instanceIndicator\" class=\"mono\">"
                + escapeHtml(instanceId)
                + "</span></div><div class=\"small text-muted mt-2\">Seed aşaması: <span id=\"maintenanceStage\" class=\"mono\">Hazir</span></div><div class=\"small text-muted mt-1\">Aşama güncellendi: <span id=\"maintenanceStageUpdatedAt\" class=\"mono\">-</span></div></div></div></div></div></div>"
                + "<div class=\"row g-3 mb-3 metric-strip\">" + metricCard("Scenario", "scenario") + metricCard("Readers", "readerThreads") + metricCard("Writers", "writerThreads") + metricCard("Reads", "reads") + metricCard("Writes", "writes") + metricCard("Errors", "errors") + metricCard("Customers", "customersCount") + metricCard("Products", "productsCount") + metricCard("Carts", "cartsCount") + metricCard("Orders", "ordersCount") + metricCard("Order Lines", "orderLinesCount") + "</div>"
                + "<div id=\"scenario-controls\" class=\"card mb-3 sticky-top-lite\"><div class=\"card-body\"><div class=\"row g-3 align-items-end\"><div class=\"col-12 col-lg-8\"><div class=\"fw-semibold mb-2\">Kontrol Butonlari</div><div class=\"d-flex flex-wrap gap-2\">"
                + "<button class=\"btn btn-primary action-btn\" id=\"seedBtn\">1. Seed Demo Data</button><button class=\"btn btn-success action-btn\" id=\"lowBtn\" data-level=\"LOW\">2. Start Low</button><button class=\"btn btn-warning action-btn\" id=\"mediumBtn\" data-level=\"MEDIUM\">3. Start Medium</button><button class=\"btn btn-danger action-btn\" id=\"highBtn\" data-level=\"HIGH\">4. Start High</button><button class=\"btn btn-outline-secondary action-btn\" id=\"stopBtn\">Stop</button><button class=\"btn btn-outline-danger action-btn\" id=\"clearBtn\">Clear Data</button><button class=\"btn btn-dark action-btn\" id=\"freshResetBtn\">Fresh Start</button><button class=\"btn btn-outline-primary action-btn\" id=\"refreshBtn\">Refresh Now</button>"
                + "</div></div><div class=\"col-12 col-lg-4\"><div class=\"fw-semibold mb-2\">Aksiyon Kuyrugu</div><div class=\"d-flex align-items-center gap-2 mb-2\"><span id=\"actionQueueBadge\" class=\"badge queue-badge text-bg-secondary\">IDLE</span><span id=\"actionQueueLabel\" class=\"helper\">Hazir</span></div><div class=\"fw-semibold mb-2\">Son Aksiyon</div><div id=\"lastActionBadge\" class=\"badge text-bg-secondary\">loading</div><div class=\"small text-muted mt-2\">Butona bastiginda istek kuyruga alinir; sayfa donmez, auto refresh ile durum akar. Low/Medium/High sadece seed hazirsa baslar.</div></div></div>"
                + "<div id=\"scenarioFocusPanel\" class=\"soft-panel mt-3\" style=\"display:none\"><div class=\"fw-semibold mb-1\">Admin konsolundan gelen senaryo odağı</div><div id=\"scenarioFocusText\" class=\"helper\">-</div></div><div class=\"small text-muted mt-3\">Last error: <span id=\"lastError\" class=\"mono\">none</span></div><div class=\"action-state mt-2\">Queued at: <span id=\"actionQueuedAt\" class=\"mono\">-</span> | Completed at: <span id=\"actionCompletedAt\" class=\"mono\">-</span></div><div class=\"action-state mt-1\">Son canli kuyruk durumu: <span id=\"lastActionStatusPollAt\" class=\"mono\">-</span></div></div></div>"
                + "<div class=\"row g-3 mb-3\"><div class=\"col-12 col-xl-6\"><div class=\"card h-100\"><div class=\"card-header fw-semibold\">Calisan Yuk Seviyesi</div><div class=\"card-body\"><div class=\"mb-2\">Aktif seviye: <span id=\"scenarioBadge\" class=\"badge text-bg-secondary\">IDLE</span></div><div id=\"scenarioSummary\" class=\"helper\">Yukleniyor...</div><div class=\"row mt-3 g-2\"><div class=\"col-6\"><div class=\"soft-panel\"><div class=\"small text-muted\">Son seed</div><div id=\"lastSeededAt\" class=\"mono\">-</div></div></div><div class=\"col-6\"><div class=\"soft-panel\"><div class=\"small text-muted\">Son start/stop</div><div id=\"lastTransitionAt\" class=\"mono\">-</div></div></div></div></div></div></div>"
                + "<div class=\"col-12 col-xl-6\"><div class=\"card h-100\"><div class=\"card-header fw-semibold\">Alttaki tablolar neyi gosteriyor?</div><div class=\"card-body\"><div class=\"helper\">Bu tablolar seed edilen ve yuk sirasinda degisen ornek kayitlarin ilk sayfasini gosterir. Artik siparis satirlari da ayri bir yuzey olarak gelir; boylece buyuk one-to-many hacminin nasil aktigini aninda gorebilirsin.</div></div></div></div></div>"
                + tableSection("Customers", "Demo musterileri. Seed hacmi buyutuldu; tum musteri listesi ve sicak musteri profili okumalarinda kullanilir.", "customersTable", new String[]{"ID", "Username", "Tier", "Status"})
                + tableSection("Products", "Demo urunleri. Buyuk katalog pencereleri ve sicak urun okumalarinda kullanilir.", "productsTable", new String[]{"ID", "SKU", "Category", "Price", "Stock"})
                + tableSection("Carts", "Demo sepetleri. Bulk quantity ve toplu checkout benzeri yazma dalgalarinda degisir.", "cartsTable", new String[]{"ID", "Customer", "Product", "Qty", "Status"})
                + tableSection("Orders", "Demo siparisleri. Fetch plan ile orderLines iliskisi toplu yuklenir; N+1 yerine batch preload yolu kullanilir.", "ordersTable", new String[]{"ID", "Customer", "Product", "Total", "Lines", "Status"})
                + tableSection("Order Lines", "Yuksek hacimli siparis satirlari. Tek seferde buyuk order line okuma ve toplu satir guncelleme dalgalarini gosterir.", "orderLinesTable", new String[]{"ID", "Order", "Product", "Line", "SKU", "Qty", "Total", "Status"})
                + "<script>let refreshInFlight=false;let statusPollInFlight=false;let actionPollInFlight=false;const apiBase='" + escapeJs(basePath) + "';const pageInstanceId='" + escapeJs(instanceId) + "';"
                + "async function call(path,method='GET',timeoutMs=1800){const controller=new AbortController();const timeout=setTimeout(()=>controller.abort(),timeoutMs);try{const requestUrl=new URL(apiBase+path,window.location.origin);if(method==='GET'){requestUrl.searchParams.set('_ts',String(Date.now()));}const response=await fetch(requestUrl.toString(),{method,signal:controller.signal,cache:'no-store',headers:{'Cache-Control':'no-cache, no-store, must-revalidate','Pragma':'no-cache','Expires':'0'}});if(!response.ok){throw new Error(await response.text());}return await response.json();}finally{clearTimeout(timeout);}}"
                + "function card(id,value){const element=document.getElementById(id);if(element){element.textContent=value;}}function fmt(ts){return ts&&ts>0?new Date(ts).toLocaleString():'-';}"
                + "function ensureLiveInstance(liveInstanceId){const incoming=String(liveInstanceId||'').trim();if(!incoming||incoming===pageInstanceId){return true;}window.location.reload();return false;}"
                + "function rows(id,items,cols){const host=document.getElementById(id);if(!host){return;}if(!items.length){host.innerHTML='<tr><td colspan=\"'+cols+'\" class=\"text-muted\">No data</td></tr>';return;}host.innerHTML=items.map(item=>'<tr>'+Object.values(item).map(v=>'<td>'+String(v)+'</td>').join('')+'</tr>').join('');}"
                + "function scenarioBadge(level,running){if(!running){return {cls:'text-bg-secondary',label:'IDLE'};}if(level==='HIGH'){return {cls:'text-bg-danger',label:'HIGH'};}if(level==='MEDIUM'){return {cls:'text-bg-warning',label:'MEDIUM'};}return {cls:'text-bg-success',label:'LOW'};}"
                + "function setScenarioBadge(level,running){const badge=scenarioBadge(level,running);const element=document.getElementById('scenarioBadge');element.className='badge '+badge.cls;element.textContent=badge.label;}"
                + "function setActionBadge(text,kind){const classes={error:'text-bg-danger',running:'text-bg-primary',done:'text-bg-success',idle:'text-bg-secondary'};const element=document.getElementById('lastActionBadge');element.className='badge '+(classes[kind]||classes.idle);element.textContent=text;}"
                + "function setQueuePollDetail(text){card('lastActionStatusPollAt',text||'-');}"
                + "function setQueueBadge(state,label){const classes={IDLE:'text-bg-secondary',QUEUED:'text-bg-warning',RUNNING:'text-bg-primary',ERROR:'text-bg-danger'};const element=document.getElementById('actionQueueBadge');element.className='badge queue-badge '+(classes[state]||classes.IDLE);element.textContent=state||'IDLE';card('actionQueueLabel',label||'Hazir');setQueuePollDetail(new Date().toLocaleTimeString());}"
                + "function setActiveButtons(level,running){document.querySelectorAll('[data-level]').forEach(btn=>btn.classList.remove('active-level'));if(running){const active=document.querySelector('[data-level=\"'+level+'\"]');if(active){active.classList.add('active-level');}}}"
                + "function scenarioFocusLabel(key){const labels={'catalog-read':'Katalog taraması','hot-product-read':'Sıcak ürün okuması','customer-profile-read':'Müşteri profil okuması','bulk-customer-read':'Toplu müşteri okuması','postgres-bulk-customer-read':'PostgreSQL toplu müşteri okuması','cart-window-read':'Sepet pencere okuması','top-customer-orders-read':'Yoğun müşteri sipariş okuması','postgres-top-customer-orders-read':'PostgreSQL yoğun müşteri sipariş okuması','high-line-orders-read':'Yüksek satırlı sipariş okuması','postgres-high-line-orders-read':'PostgreSQL yüksek satırlı sipariş okuması','order-window-with-lines-read':'Sipariş ve satır detay okuması','product-write-burst':'Ürün yazma burst\\'ü','cart-write-burst':'Sepet yazma burst\\'ü','customer-write-burst':'Müşteri yazma burst\\'ü','order-write-burst':'Sipariş yazma burst\\'ü','order-line-write-burst':'Sipariş satırı yazma burst\\'ü'};return labels[key]||String(key||'-').replace(/-/g,' ');}"
                + "function applyScenarioFocus(){const params=new URLSearchParams(window.location.search);const focus=params.get('focus');const level=(params.get('level')||'').toUpperCase();const panel=document.getElementById('scenarioFocusPanel');const text=document.getElementById('scenarioFocusText');if(!focus||!panel||!text){if(panel){panel.style.display='none';}return;}const levelText=(level==='HIGH'||level==='MEDIUM'||level==='LOW')?level:'MEDIUM';panel.style.display='block';text.textContent='İzlenecek akış: '+scenarioFocusLabel(focus)+'. Önerilen yeniden üretim seviyesi: '+levelText+'. Bu kart, admin performans özetinden bu yük yoluna hızlı dönmen için açıldı.';const suggested=document.querySelector('[data-level=\"'+levelText+'\"]');if(suggested){suggested.classList.add('active-level');suggested.scrollIntoView({behavior:'smooth',block:'center'});}}"
                + "function pulseButton(button){if(!button){return;}button.classList.add('request-busy');setTimeout(()=>button.classList.remove('request-busy'),900);}"
                + "function applyStatusData(data){if(!ensureLiveInstance(data&&data.instanceId)){return null;}const snapshot=data.snapshot;const action=data.action||{};card('instanceIndicator',data.instanceId||pageInstanceId);card('scenario',snapshot.activeScenarioLabel);card('readerThreads',snapshot.activeReaderThreads);card('writerThreads',snapshot.activeWriterThreads);card('reads',snapshot.readCount);card('writes',snapshot.writeCount);card('errors',snapshot.errorCount);card('customersCount',snapshot.customerCount);card('productsCount',snapshot.productCount);card('cartsCount',snapshot.cartCount);card('ordersCount',snapshot.orderCount);card('orderLinesCount',snapshot.orderLineCount);card('seedSummary',snapshot.seedSummary);card('currentAction',snapshot.currentAction);card('maintenanceStage',snapshot.maintenanceStage||'Hazir');card('maintenanceStageUpdatedAt',fmt(snapshot.maintenanceStageUpdatedAtEpochMillis));card('scenarioSummary',snapshot.activeScenarioSummary);card('lastSeededAt',fmt(snapshot.lastSeededAtEpochMillis));card('lastTransitionAt',snapshot.running?fmt(snapshot.lastStartedAtEpochMillis):fmt(snapshot.lastStoppedAtEpochMillis));card('lastError',snapshot.lastError||'none');card('actionQueuedAt',fmt(action.lastQueuedAtEpochMillis));card('actionCompletedAt',fmt(action.lastCompletedAtEpochMillis));setScenarioBadge(snapshot.activeScenario,snapshot.running);setActiveButtons(snapshot.activeScenario,snapshot.running);applyScenarioFocus();setQueueBadge(action.state,action.label);if(action.state==='ERROR'&&action.error){setActionBadge(action.error,'error');}else if(action.state==='RUNNING'){setActionBadge(action.label||'Islem calisiyor','running');}else if(action.state==='QUEUED'){setActionBadge((action.label||'Islem')+' kuyruga alindi','running');}else if(snapshot.running){setActionBadge(snapshot.currentAction,'running');}else{setActionBadge(snapshot.currentAction,'done');}return action;}"
                + "function applyActionState(action){if(!action||!ensureLiveInstance(action.instanceId)){return;}setQueueBadge(action.state,action.label);card('instanceIndicator',action.instanceId||pageInstanceId);card('actionQueuedAt',fmt(action.lastQueuedAtEpochMillis));card('actionCompletedAt',fmt(action.lastCompletedAtEpochMillis));if(action.state==='ERROR'&&action.error){setActionBadge(action.error,'error');}else if(action.state==='RUNNING'){setActionBadge(action.label||'Islem calisiyor','running');}else if(action.state==='QUEUED'){setActionBadge((action.label||'Islem')+' kuyruga alindi','running');}}"
                + "async function refreshActionStatus(){if(actionPollInFlight){return null;}actionPollInFlight=true;try{const action=await call('/action-status','GET',3200);applyActionState(action);return action;}catch(error){const reason=(error&&error.name==='AbortError')?'timeout':(error&&error.message?error.message:'poll failed');setQueuePollDetail(new Date().toLocaleTimeString()+' ('+reason+')');return null;}finally{actionPollInFlight=false;}}"
                + "async function refreshStatus(){if(statusPollInFlight){return null;}statusPollInFlight=true;try{const data=await call('/status','GET',4500);applyStatusData(data);return data;}catch(error){const reason=(error&&error.name==='AbortError')?'timeout':(error&&error.message?error.message:'status failed');setQueuePollDetail(new Date().toLocaleTimeString()+' ('+reason+')');throw error;}finally{statusPollInFlight=false;}}"
                + "async function watchActionQueue(maxAttempts=40,delayMs=500){let lastAction=null;for(let i=0;i<maxAttempts;i++){lastAction=await refreshActionStatus();if(lastAction&&lastAction.state&&lastAction.state!=='QUEUED'&&lastAction.state!=='RUNNING'){await refreshStatus().catch(()=>{});return lastAction;}await new Promise(resolve=>setTimeout(resolve,delayMs));}await refreshStatus().catch(()=>{});return lastAction;}"
                + "async function refresh(){if(refreshInFlight){return;}refreshInFlight=true;try{await refreshStatus();const views=await call('/views','GET',6000);rows('customersTable',views.customers,4);rows('productsTable',views.products,5);rows('cartsTable',views.carts,5);rows('ordersTable',views.orders,6);rows('orderLinesTable',views.orderLines,8);}catch(error){setActionBadge(error&&error.name==='AbortError'?'Refresh zaman asimina ugradi':(error.message||'Refresh basarisiz'),'error');}finally{refreshInFlight=false;}}"
                + "async function doAction(path,method,label,button){pulseButton(button);setActionBadge(label+' gonderildi','running');try{const ack=await call(path,method,1200);if(ack&&ack.action){applyActionState(ack.action);}await watchActionQueue();}catch(error){if(error&&error.name!=='AbortError'){setActionBadge(error.message||'Istek basarisiz','error');}}finally{setTimeout(()=>refresh().catch(()=>{}),150);}}"
                + "document.getElementById('seedBtn').addEventListener('click',event=>doAction('/seed','POST','Seed islemi',event.currentTarget));document.getElementById('lowBtn').addEventListener('click',event=>doAction('/start?level=LOW','POST','Low yuk',event.currentTarget));document.getElementById('mediumBtn').addEventListener('click',event=>doAction('/start?level=MEDIUM','POST','Medium yuk',event.currentTarget));document.getElementById('highBtn').addEventListener('click',event=>doAction('/start?level=HIGH','POST','High yuk',event.currentTarget));document.getElementById('stopBtn').addEventListener('click',event=>doAction('/stop','POST','Stop',event.currentTarget));document.getElementById('clearBtn').addEventListener('click',event=>doAction('/clear','POST','Clear Data',event.currentTarget));document.getElementById('freshResetBtn').addEventListener('click',event=>doAction('/fresh-reset','POST','Fresh Start',event.currentTarget));document.getElementById('refreshBtn').addEventListener('click',()=>refresh());"
                + "if(" + tuning.uiAutoRefreshMillis() + ">0){setInterval(()=>refreshActionStatus().catch(()=>{}),1200);setInterval(()=>refreshStatus().catch(()=>{})," + Math.max(1200, Math.min(tuning.uiAutoRefreshMillis(), 2500)) + ");setInterval(()=>refresh().catch(()=>{})," + tuning.uiAutoRefreshMillis() + ");}applyScenarioFocus();refresh();"
                + "</script></main></body></html>";
    }

    private static String renderActionState(DemoScenarioActionStateSnapshot actionState, String instanceId) {
        return "{\"instanceId\":\"" + escapeJson(instanceId) + "\",\"state\":\"" + escapeJson(actionState.state()) + "\",\"label\":\"" + escapeJson(actionState.label()) + "\",\"error\":\"" + escapeJson(actionState.error()) + "\",\"lastQueuedAtEpochMillis\":" + actionState.lastQueuedAtEpochMillis() + ",\"lastCompletedAtEpochMillis\":" + actionState.lastCompletedAtEpochMillis() + ",\"lastActionId\":" + actionState.lastActionId() + "}";
    }

    private static String renderSnapshot(DemoScenarioSnapshot snapshot, DemoScenarioService service) {
        return "{\"seeded\":" + snapshot.seeded() + ",\"running\":" + snapshot.running() + ",\"activeScenario\":\"" + escapeJson(snapshot.activeScenario()) + "\",\"activeScenarioLabel\":\"" + escapeJson(snapshot.activeScenarioLabel()) + "\",\"activeScenarioSummary\":\"" + escapeJson(snapshot.activeScenarioSummary()) + "\",\"activeReaderThreads\":" + snapshot.activeReaderThreads() + ",\"activeWriterThreads\":" + snapshot.activeWriterThreads() + ",\"customerCount\":" + snapshot.customerCount() + ",\"productCount\":" + snapshot.productCount() + ",\"cartCount\":" + snapshot.cartCount() + ",\"orderCount\":" + snapshot.orderCount() + ",\"orderLineCount\":" + snapshot.orderLineCount() + ",\"readCount\":" + snapshot.readCount() + ",\"writeCount\":" + snapshot.writeCount() + ",\"errorCount\":" + snapshot.errorCount() + ",\"seedSummary\":\"" + escapeJson(snapshot.seedSummary()) + "\",\"currentAction\":\"" + escapeJson(snapshot.currentAction()) + "\",\"maintenanceStage\":\"" + escapeJson(service.maintenanceStage()) + "\",\"maintenanceInProgress\":" + service.maintenanceInProgress() + ",\"maintenanceStageUpdatedAtEpochMillis\":" + service.maintenanceStageUpdatedAtEpochMillis() + ",\"lastSeededAtEpochMillis\":" + snapshot.lastSeededAtEpochMillis() + ",\"lastStartedAtEpochMillis\":" + snapshot.lastStartedAtEpochMillis() + ",\"lastStoppedAtEpochMillis\":" + snapshot.lastStoppedAtEpochMillis() + ",\"lastError\":\"" + escapeJson(snapshot.lastError()) + "\",\"recordedAt\":\"" + snapshot.recordedAt() + "\"}";
    }

    private static String renderCustomers(List<DemoCustomerEntity> items) { return renderArray(items, item -> "{\"id\":" + item.id + ",\"username\":\"" + escapeJson(item.username) + "\",\"tier\":\"" + escapeJson(item.tier) + "\",\"status\":\"" + escapeJson(item.status) + "\"}"); }
    private static String renderProducts(List<DemoProductEntity> items) { return renderArray(items, item -> "{\"id\":" + item.id + ",\"sku\":\"" + escapeJson(item.sku) + "\",\"category\":\"" + escapeJson(item.category) + "\",\"price\":" + item.price + ",\"stock\":" + item.stock + "}"); }
    private static String renderCarts(List<DemoCartEntity> items) { return renderArray(items, item -> "{\"id\":" + item.id + ",\"customerId\":" + item.customerId + ",\"productId\":" + item.productId + ",\"quantity\":" + item.quantity + ",\"status\":\"" + escapeJson(item.status) + "\"}"); }
    private static String renderOrders(List<DemoOrderEntity> items) { return renderArray(items, item -> "{\"id\":" + item.id + ",\"customerId\":" + item.customerId + ",\"productId\":" + item.productId + ",\"totalAmount\":" + item.totalAmount + ",\"lineItemCount\":" + item.lineItemCount + ",\"status\":\"" + escapeJson(item.status) + "\"}"); }
    private static String renderOrderLines(List<DemoOrderLineEntity> items) { return renderArray(items, item -> "{\"id\":" + item.id + ",\"orderId\":" + item.orderId + ",\"productId\":" + item.productId + ",\"lineNumber\":" + item.lineNumber + ",\"sku\":\"" + escapeJson(item.sku) + "\",\"quantity\":" + item.quantity + ",\"lineTotal\":" + item.lineTotal + ",\"status\":\"" + escapeJson(item.status) + "\"}"); }

    private static <T> String renderArray(List<T> items, java.util.function.Function<T, String> rowRenderer) {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < items.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(rowRenderer.apply(items.get(index)));
        }
        builder.append(']');
        return builder.toString();
    }

    private static String metricCard(String title, String id) {
        return "<div class=\"col-6 col-lg-3 col-xl-2\"><div class=\"card\"><div class=\"card-body\"><div class=\"text-muted small\">"
                + escapeHtml(title) + "</div><div class=\"metric\" id=\"" + escapeHtml(id) + "\">0</div></div></div></div>";
    }

    private static String tableSection(String title, String description, String tableId, String[] headers) {
        StringBuilder head = new StringBuilder();
        for (String header : headers) {
            head.append("<th>").append(escapeHtml(header)).append("</th>");
        }
        return "<div class=\"card mb-3\"><div class=\"card-header fw-semibold\">" + escapeHtml(title) + "</div><div class=\"card-body\"><div class=\"section-note mb-2\">" + escapeHtml(description) + "</div><div class=\"table-responsive\"><table class=\"table table-sm table-hover mb-0\"><thead><tr>" + head + "</tr></thead><tbody id=\"" + escapeHtml(tableId) + "\"><tr><td colspan=\"" + headers.length + "\" class=\"text-muted\">Loading...</td></tr></tbody></table></div></div></div>";
    }

    private static String normalizeApiBasePath(String apiBasePath) {
        if (apiBasePath == null || apiBasePath.isBlank() || "/".equals(apiBasePath.trim())) {
            return "";
        }
        String normalized = apiBasePath.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String escapeJson(String value) { return defaultString(value).replace("\\", "\\\\").replace("\"", "\\\""); }
    private static String escapeHtml(String value) { return defaultString(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
    private static String escapeJs(String value) { return escapeJson(value).replace("'", "\\'"); }
    private static String defaultString(String value) { return Objects.requireNonNullElse(value, ""); }
}
