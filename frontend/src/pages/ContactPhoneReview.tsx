import { useState } from 'react';
import { post } from '../api';
import { ErrorBox, Panel } from '../components';

export function ContactPhoneReview({id,revision,onUpdate}:{id:string;revision:number;onUpdate:()=>void}) {
  const [note,setNote]=useState('');const [busy,setBusy]=useState(false);const [error,setError]=useState('');
  return <Panel title="Independent phone verification"><form className="panel-body" onSubmit={async e=>{e.preventDefault();setBusy(true);setError('');try{await post(`/api/documents/submissions/${encodeURIComponent(id)}/verify-phone`,{revision,verificationNote:note});setNote('');onUpdate();}catch(e){setError(e instanceof Error?e.message:'Unable to record verification.');}finally{setBusy(false);}}}><p>Email authorisation does not prove ownership of a new phone number. Record this only after completing the separate synthetic verification procedure. No SMS provider is connected.</p><label>Verification method and evidence<input required minLength={12} maxLength={500} value={note} onChange={e=>setNote(e.target.value)}/></label><button className="button secondary" disabled={busy||note.trim().length<12}>Record completed phone verification</button>{error&&<ErrorBox message={error}/>}</form></Panel>;
}
