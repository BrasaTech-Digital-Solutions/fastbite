(() => {
'use strict';
const token=document.querySelector('meta[name="csrf-token"]').content;
const header=document.querySelector('meta[name="csrf-header"]').content;
const feedback=document.getElementById('workspace-feedback');
function message(text,error=false){feedback.textContent=text;feedback.hidden=false;feedback.className='alert '+(error?'error':'success');feedback.scrollIntoView({block:'center',behavior:'smooth'});}
async function request(url,body,html=false){const response=await fetch(url,{method:'POST',headers:{[header]:token},body});if(!response.ok || response.redirected){
 // A conflict explains itself, such as a production bundle from another FastBite version
 let reason='The action could not be completed. Check your session and try again.';
 if(response.status===409){try{reason=(await response.json()).message||reason;}catch(e){}}
 throw new Error(reason);}
 return html?response.text():response.json();}
function busy(form,value){form.querySelectorAll('button').forEach(b=>b.disabled=value);form.setAttribute('aria-busy',String(value));}
// Replace only the server-rendered team content, preserving the workspace.
document.addEventListener('submit',async event=>{
 const form=event.target;if(form.id!=='add-staff')return;event.preventDefault();busy(form,true);
 try{document.getElementById('staff-panel').innerHTML=await request('/owner/locations/'+encodeURIComponent(form.dataset.tenant)+'/users',new URLSearchParams(new FormData(form)),true);}catch(e){message(e.message,true);}finally{busy(form,false);}
});
document.addEventListener('click',async event=>{
 const button=event.target.closest('[data-delete-staff]');if(!button)return;
 const msg=(button.dataset.confirmTemplate||'Remove this staff account from {0}? They will lose access to this location.').replace('{0}',button.dataset.tenant||'');
 if(!await fbConfirm({
  message: msg,
  confirmText: button.dataset.confirmButton||'Remove staff',
  cancelText: button.dataset.cancelButton||'Cancel',
  danger: true
 }))return;
 button.disabled=true;try{document.getElementById('staff-panel').innerHTML=await request('/owner/locations/'+encodeURIComponent(button.dataset.tenant)+'/users/delete',new URLSearchParams({userId:button.dataset.user}),true);}catch(e){message(e.message,true);button.disabled=false;}
});
const dialog=document.getElementById('maintenance-dialog');const confirmForm=document.getElementById('maintenance-confirm');const confirmName=document.getElementById('confirmation-name');
const tplGroup=document.getElementById('maintenance-template-group');const tplSelect=document.getElementById('maintenance-template-select');const tplDesc=document.getElementById('maintenance-template-desc');
let pending=null;let reviewedFile=null;
async function review(tenant,action,file){
 pending={tenant,action,file};confirmForm.reset();confirmName.setCustomValidity('');document.getElementById('maintenance-error').textContent='';
 document.getElementById('maintenance-title').textContent=action==='clean'?'Reset this restaurant?':action==='restore-demo'?'Load the demo template?':'Restore your backup?';
 document.getElementById('maintenance-description').textContent=action==='clean'?'This permanently removes this location’s menu, orders, staff and uploaded files. Tickets and tax records are kept by law and won’t be deleted.':action==='restore-demo'?'This replaces this location’s menu, settings and files with the sample restaurant and clears existing orders and staff. No sample logins or historical orders are imported.':'This replaces this location’s menu, orders, staff, settings and uploaded files with the backup you checked.';
 document.getElementById('maintenance-location').textContent=tenant;document.getElementById('maintenance-export').href='/'+encodeURIComponent(tenant)+'/api/backoffice/maintenance/backup';
 if(tplGroup){
  tplGroup.hidden=(action!=='restore-demo');
  if(action==='restore-demo'&&tplSelect){
   if(tplSelect.options.length===0){
    try{
     const res=await fetch('/'+encodeURIComponent(tenant)+'/api/backoffice/maintenance/templates');
     if(res.ok){
      const list=await res.json();
      tplSelect.innerHTML='';
      list.forEach(t=>{const opt=document.createElement('option');opt.value=t.id;opt.dataset.desc=t.description||'';opt.textContent=(t.icon?t.icon+' ':'')+t.name+' ('+t.productsCount+' items)';tplSelect.appendChild(opt);});
     }
    }catch(err){}
   }
   const updateDesc=()=>{const opt=tplSelect.options[tplSelect.selectedIndex];if(tplDesc)tplDesc.textContent=opt?(opt.dataset.desc||''):'';};
   tplSelect.onchange=updateDesc;updateDesc();
  }
 }
 dialog.showModal();confirmName.focus();
}
document.querySelectorAll('[data-maintenance]').forEach(button=>button.addEventListener('click',()=>review(button.dataset.tenant,button.dataset.maintenance,null)));
document.getElementById('maintenance-cancel').addEventListener('click',()=>dialog.close());
confirmName.addEventListener('input',()=>confirmName.setCustomValidity(''));
confirmForm.addEventListener('submit',async event=>{
 event.preventDefault();if(confirmName.value!==pending.tenant){confirmName.setCustomValidity('Enter '+pending.tenant+' exactly to confirm.');confirmName.reportValidity();return;}
 busy(confirmForm,true);
 try{
  let endpoint='/'+encodeURIComponent(pending.tenant)+'/api/backoffice/maintenance/'+pending.action;
  let body;
  if(pending.action==='restore-demo'){
   if(tplSelect&&tplSelect.value)endpoint+='?template='+encodeURIComponent(tplSelect.value);
  }else if(pending.file){
   body=new FormData();body.append('file',pending.file);
  }
  const res=await request(endpoint,body);
  dialog.close();
  if(res&&res.notice&&typeof fbNotice==='function'){await fbNotice({message:res.notice});}
  window.location.assign('/owner/console?location='+encodeURIComponent(pending.tenant));
 }catch(e){document.getElementById('maintenance-error').textContent=e.message+' Your setup has not been marked complete.';}finally{busy(confirmForm,false);}
});
// Restore the exact File object reviewed, not a subsequently changed selection.
const backupForm=document.getElementById('backup-preview');
if(backupForm){const fileInput=document.getElementById('backup-file');const result=document.getElementById('backup-result');fileInput.addEventListener('change',()=>{reviewedFile=null;result.hidden=true;});backupForm.addEventListener('submit',async event=>{event.preventDefault();result.hidden=true;reviewedFile=null;const file=fileInput.files[0];if(!file)return;busy(backupForm,true);try{const body=new FormData();body.append('file',file);const summary=await request('/owner/workspace/'+encodeURIComponent(backupForm.dataset.tenant)+'/backup-preview',body);if(fileInput.files[0]!==file)return;reviewedFile=file;document.getElementById('backup-summary').textContent='Archive checked: '+summary.products+' menu items, '+summary.orders+' orders and '+summary.accounts+' account records.';result.hidden=false;}catch(e){message('We could not validate this backup. Use an exported FastBite ZIP and check that you are still signed in.',true);}finally{busy(backupForm,false);}});document.getElementById('restore-reviewed').addEventListener('click',()=>{if(reviewedFile)review(backupForm.dataset.tenant,'restore',reviewedFile);});}
const promoteBtn = document.getElementById('promote-preview-btn');
if (promoteBtn) {
 const tenant = promoteBtn.dataset.tenant;
 const previewResult = document.getElementById('promote-preview-result');
 const diffList = document.getElementById('promote-diff-list');
 const productChangesDiv = document.getElementById('promote-product-changes');
 const removedProductsDiv = document.getElementById('promote-removed-products');
 const warningEl = document.getElementById('promote-warning');
 const confirmBtn = document.getElementById('promote-confirm-btn');
 const cancelBtn = document.getElementById('promote-cancel-btn');
 const errorEl = document.getElementById('promote-error');
 let currentToken = null;

 const fmtPrice = (val) => {
  if (val == null) return '';
  return new Intl.NumberFormat(document.documentElement.lang || 'es-ES', { style: 'currency', currency: 'EUR' }).format(val);
 };

 promoteBtn.addEventListener('click', async () => {
  const origText = promoteBtn.textContent;
  const checkingText = promoteBtn.dataset.checkingText || 'Checking your sandbox…';
  promoteBtn.disabled = true;
  promoteBtn.textContent = checkingText;
  if (errorEl) { errorEl.hidden = true; errorEl.textContent = ''; }
  if (previewResult) { previewResult.hidden = true; }
  try {
   const res = await fetch('/owner/workspace/' + encodeURIComponent(tenant) + '/promote/preview', {
    method: 'POST',
    headers: { [header]: token }
   });
   let data = null;
   try {
    data = await res.json();
   } catch (_) {
    data = null;
   }
   if (!res.ok) {
    const genericErr = promoteBtn.dataset.genericError || 'Could not check sandbox setup';
    throw new Error((data && data.message) ? data.message : genericErr);
   }
   currentToken = data.token;
   diffList.innerHTML = '';
   const addDiff = (label, diff) => {
    if (!diff || (diff.added === 0 && diff.changed === 0 && diff.removed === 0)) return;
    const li = document.createElement('li');
    li.textContent = label + ': ' + diff.added + ' added, ' + diff.changed + ' changed, ' + diff.removed + ' removed';
    diffList.appendChild(li);
   };
   addDiff('Products', data.products);
   addDiff('Categories', data.categories);
   addDiff('Customizations', data.customizations);
   addDiff('Tables', data.tables);
   addDiff('Discounts', data.discounts);
   if (data.theme && data.theme.before !== data.theme.after) {
    const li = document.createElement('li');
    const tBefore = data.theme.before || 'default';
    const tAfter = data.theme.after || 'default';
    li.textContent = 'Visual theme: ' + tBefore + ' → ' + tAfter;
    diffList.appendChild(li);
   }
   if (data.serviceSettingsReplaced) {
    const li = document.createElement('li');
    li.textContent = 'Business details and service settings will be replaced from the sandbox';
    diffList.appendChild(li);
   }
   if (diffList.children.length === 0) {
    const li = document.createElement('li');
    li.textContent = 'No changes to menu or configuration detected.';
    diffList.appendChild(li);
   }

   const priceChanges = [];
   const otherChanges = [];
   (data.productChanges || []).forEach(pc => {
    const pBefore = pc.priceBefore != null ? Number(pc.priceBefore) : null;
    const pAfter = pc.priceAfter != null ? Number(pc.priceAfter) : null;
    if (pBefore !== pAfter) {
     priceChanges.push(pc);
    } else {
     otherChanges.push(pc.name);
    }
   });

   productChangesDiv.innerHTML = '';
   if (priceChanges.length > 0) {
    const title = document.createElement('strong');
    title.textContent = 'Price changes:';
    productChangesDiv.appendChild(title);
    const ul = document.createElement('ul');
    ul.style.paddingLeft = '1.2rem';
    ul.style.margin = '0.25rem 0';
    priceChanges.forEach(pc => {
     const li = document.createElement('li');
     let desc = pc.name + ': ' + fmtPrice(pc.priceBefore) + ' → ' + fmtPrice(pc.priceAfter);
     if (pc.allergensChanged) desc += ' (allergens changed)';
     li.textContent = desc;
     ul.appendChild(li);
    });
    productChangesDiv.appendChild(ul);
   }
   if (otherChanges.length > 0) {
    const p = document.createElement('p');
    p.style.margin = '0.25rem 0';
    p.innerHTML = '<strong>Other product changes:</strong> ' + otherChanges.join(', ');
    productChangesDiv.appendChild(p);
   }
   productChangesDiv.hidden = (priceChanges.length === 0 && otherChanges.length === 0);

   if (data.products && data.products.removedNames && data.products.removedNames.length > 0) {
    removedProductsDiv.innerHTML = '<strong>Removed products:</strong> ' + data.products.removedNames.join(', ');
    removedProductsDiv.hidden = false;
   } else {
    removedProductsDiv.hidden = true;
   }

   const po = data.pricesOverwritten || 0;
   const pr = (data.products && data.products.removed) ? data.products.removed : 0;
   const wPricesOne = previewResult.dataset.warnPricesOne || '1 price changed directly in production will be replaced';
   const wPricesMany = previewResult.dataset.warnPricesMany || '{0} prices changed directly in production will be replaced';
   const wRemovedOne = previewResult.dataset.warnRemovedOne || '1 product that exists only in production will be removed';
   const wRemovedMany = previewResult.dataset.warnRemovedMany || '{0} products that exist only in production will be removed';
   const wGeneral = previewResult.dataset.warnGeneral || 'Promoting replaces changes made directly in production. A backup of this location will be taken automatically before applying.';

   let parts = [];
   if (po > 0) {
    parts.push(po === 1 ? wPricesOne : wPricesMany.replace('{0}', String(po)));
   }
   if (pr > 0) {
    parts.push(pr === 1 ? wRemovedOne : wRemovedMany.replace('{0}', String(pr)));
   }
   let warn = '';
   if (parts.length > 0) {
    warn = parts.join(', ') + '. ';
   }
   warn += wGeneral;
   warningEl.textContent = warn;

   previewResult.hidden = false;
  } catch (err) {
   if (errorEl) {
    errorEl.textContent = err.message;
    errorEl.hidden = false;
   } else {
    message(err.message, true);
   }
  } finally {
   promoteBtn.disabled = false;
   promoteBtn.textContent = origText;
  }
 });

 if (cancelBtn) {
  cancelBtn.addEventListener('click', () => {
   previewResult.hidden = true;
   currentToken = null;
  });
 }

 if (confirmBtn) {
  confirmBtn.addEventListener('click', async () => {
   if (!currentToken) return;
   confirmBtn.disabled = true;
   if (cancelBtn) cancelBtn.disabled = true;
   try {
    const res = await fetch('/owner/workspace/' + encodeURIComponent(tenant) + '/promote/confirm', {
     method: 'POST',
     headers: {
      [header]: token,
      'Content-Type': 'application/json'
     },
     body: JSON.stringify({ token: currentToken })
    });
    let data = null;
    try {
     data = await res.json();
    } catch (_) {
     data = null;
    }
    if (!res.ok) {
     const genericErr = confirmBtn.dataset.genericError || 'Could not apply promotion';
     throw new Error((data && data.message) ? data.message : genericErr);
    }
    if (typeof fbNotice === 'function') {
     await fbNotice({ message: 'Promotion applied successfully. Pre-promotion snapshot saved.' });
    }
    window.location.assign('/owner/console?view=location-settings&section=backup&location=' + encodeURIComponent(tenant));
   } catch (err) {
    if (errorEl) {
     errorEl.textContent = err.message;
     errorEl.hidden = false;
    } else {
     message(err.message, true);
    }
    confirmBtn.disabled = false;
    if (cancelBtn) cancelBtn.disabled = false;
   }
  });
 }
}

const undoBtn = document.getElementById('promote-undo-btn');
if (undoBtn) {
 const tenant = undoBtn.dataset.tenant;
 const undoError = document.getElementById('promote-undo-error');
 undoBtn.addEventListener('click', async () => {
  const msg = undoBtn.dataset.confirm || 'Are you sure you want to undo the last promotion? This will restore the pre-promotion snapshot.';
  let confirmed = false;
  if (typeof fbConfirm === 'function') {
   confirmed = await fbConfirm({
    message: msg,
    confirmText: undoBtn.textContent.trim(),
    cancelText: 'Cancel',
    danger: true
   });
  } else {
   confirmed = window.confirm(msg);
  }
  if (!confirmed) return;

  undoBtn.disabled = true;
  if (undoError) { undoError.hidden = true; undoError.textContent = ''; }
  try {
   const res = await fetch('/owner/workspace/' + encodeURIComponent(tenant) + '/promote/undo', {
    method: 'POST',
    headers: { [header]: token }
   });
   let data = null;
   try {
    data = await res.json();
   } catch (_) {
    data = null;
   }
   if (!res.ok) {
    const genericErr = undoBtn.dataset.genericError || 'Could not undo promotion';
    throw new Error((data && data.message) ? data.message : genericErr);
   }
   if (typeof fbNotice === 'function') {
    await fbNotice({ message: 'Last promotion has been undone.' });
   }
   window.location.assign('/owner/console?view=location-settings&section=backup&location=' + encodeURIComponent(tenant));
  } catch (err) {
   if (undoError) {
    undoError.textContent = err.message;
    undoError.hidden = false;
   } else {
    message(err.message, true);
   }
   undoBtn.disabled = false;
  }
 });
}
function openSection(){if(location.hash==='#add-location'){const item=document.getElementById('add-location');if(item)item.open=true;}}
window.addEventListener('hashchange',openSection);openSection();
})();

// Preview stays on the selected location's origin; no theme state is stored in the browser.
(() => {
 const dialog=document.getElementById('guest-preview-dialog');
 if(!dialog)return;
 const frame=document.getElementById('guest-preview-frame');
 let trigger=null;
 document.addEventListener('click',event=>{
  const button=event.target.closest('[data-guest-preview]');
  if(!button)return;
  event.preventDefault();trigger=button;
  const url=new URL(button.dataset.url,window.location.origin);
  if(url.origin!==window.location.origin)return;
  document.getElementById('guest-preview-open').href=url.pathname;
  url.searchParams.set('preview','guest');frame.src=url.pathname+url.search;
  dialog.showModal();
 });
 document.getElementById('guest-preview-close').addEventListener('click',()=>dialog.close());
 dialog.addEventListener('close',()=>{frame.removeAttribute('src');trigger?.focus();});
 dialog.querySelectorAll('[data-preview-width]').forEach(button=>button.addEventListener('click',()=>{
  frame.classList.toggle('mobile',button.dataset.previewWidth==='mobile');
  dialog.querySelectorAll('[data-preview-width]').forEach(option=>option.setAttribute('aria-pressed',String(option===button)));
 }));
})();
// Onboarding Archetype Selection
(() => {
  const cards = document.querySelectorAll('.archetype-card');
  const tplInput = document.getElementById('selected-template-input');
  const themeInput = document.getElementById('selected-theme-input');
  const dishEm = document.getElementById('motion-current-dish');
  if(!cards.length || !tplInput || !themeInput) return;

  cards.forEach(card => {
    card.addEventListener('click', () => {
      cards.forEach(c => c.classList.remove('selected'));
      card.classList.add('selected');
      tplInput.value = card.dataset.archetype || 'kebab';
      themeInput.value = card.dataset.theme || 'fastfood';
      if(dishEm && card.dataset.dish) {
        dishEm.textContent = card.dataset.dish;
      }
    });
  });
})();
