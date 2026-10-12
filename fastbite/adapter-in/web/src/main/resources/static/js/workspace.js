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
 if(!confirm('Remove this staff account from '+button.dataset.tenant+'? They will lose access to this location.'))return;
 button.disabled=true;try{document.getElementById('staff-panel').innerHTML=await request('/owner/locations/'+encodeURIComponent(button.dataset.tenant)+'/users/delete',new URLSearchParams({userId:button.dataset.user}),true);}catch(e){message(e.message,true);button.disabled=false;}
});
const dialog=document.getElementById('maintenance-dialog');const confirmForm=document.getElementById('maintenance-confirm');const confirmName=document.getElementById('confirmation-name');
const tplGroup=document.getElementById('maintenance-template-group');const tplSelect=document.getElementById('maintenance-template-select');const tplDesc=document.getElementById('maintenance-template-desc');
let pending=null;let reviewedFile=null;
async function review(tenant,action,file){
 pending={tenant,action,file};confirmForm.reset();confirmName.setCustomValidity('');document.getElementById('maintenance-error').textContent='';
 document.getElementById('maintenance-title').textContent=action==='clean'?'Reset this restaurant?':action==='restore-demo'?'Load the demo template?':'Restore your backup?';
 document.getElementById('maintenance-description').textContent=action==='clean'?'This permanently removes this location’s menu, orders, staff and uploaded files.':action==='restore-demo'?'This replaces this location’s menu, settings and files with the sample restaurant and clears existing orders and staff. No sample logins or historical orders are imported.':'This replaces this location’s menu, orders, staff, settings and uploaded files with the backup you checked.';
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
  await request(endpoint,body);
  dialog.close();window.location.assign('/owner/console?location='+encodeURIComponent(pending.tenant));
 }catch(e){document.getElementById('maintenance-error').textContent=e.message+' Your setup has not been marked complete.';}finally{busy(confirmForm,false);}
});
// Restore the exact File object reviewed, not a subsequently changed selection.
const backupForm=document.getElementById('backup-preview');
if(backupForm){const fileInput=document.getElementById('backup-file');const result=document.getElementById('backup-result');fileInput.addEventListener('change',()=>{reviewedFile=null;result.hidden=true;});backupForm.addEventListener('submit',async event=>{event.preventDefault();result.hidden=true;reviewedFile=null;const file=fileInput.files[0];if(!file)return;busy(backupForm,true);try{const body=new FormData();body.append('file',file);const summary=await request('/owner/workspace/'+encodeURIComponent(backupForm.dataset.tenant)+'/backup-preview',body);if(fileInput.files[0]!==file)return;reviewedFile=file;document.getElementById('backup-summary').textContent='Archive checked: '+summary.products+' menu items, '+summary.orders+' orders and '+summary.accounts+' account records.';result.hidden=false;}catch(e){message('We could not validate this backup. Use an exported FastBite ZIP and check that you are still signed in.',true);}finally{busy(backupForm,false);}});document.getElementById('restore-reviewed').addEventListener('click',()=>{if(reviewedFile)review(backupForm.dataset.tenant,'restore',reviewedFile);});}
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
