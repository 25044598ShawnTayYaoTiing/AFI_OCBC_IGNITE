import type { Detail, Field } from "../document-types";
import { label } from "../api";
export function CaseComparison({detail}:{detail:Detail}) {
 const profile=detail.checks.flatMap(check=>check.evidence).filter(field=>field.method==="SYNTHETIC_CUSTOMER_PROFILE");
 const declarations=detail.documents.filter(doc=>doc.document_type!=="SUPPORTING_DOCUMENT").flatMap(doc=>doc.fields);
 const supporting=detail.documents.filter(doc=>doc.document_type==="SUPPORTING_DOCUMENT").flatMap(doc=>doc.fields);
 const groups=[{title:"Existing bank records",note:"Synthetic profile evidence returned with the checks",fields:profile},{title:"Customer declaration",note:"Values captured from the submitted request",fields:declarations},{title:"Supporting evidence",note:"Extracted values from supporting documents",fields:supporting}];
 function unique(fields:Field[]){return fields.filter((field,index)=>fields.findIndex(other=>other.field_name===field.field_name&&other.field_value===field.field_value)===index).slice(0,4);}
 return <section className="evidence-comparison" aria-label="Evidence comparison"><header><span className="edition-label">Cross-source review</span><h2>Compare before you decide.</h2><p>Source values are evidence, not an approval. Review the findings and policy checks below.</p></header><div className="comparison-columns">{groups.map(group=><article key={group.title}><h3>{group.title}</h3><p>{group.note}</p>{group.fields.length?<dl>{unique(group.fields).map(field=><div key={field.field_name+field.field_value}><dt>{label(field.field_name)}</dt><dd>{field.field_value}</dd><small>{field.filename||"Profile evidence"}{field.page_number?` / page ${field.page_number}`:" / no PDF page"}</small></div>)}</dl>:<p className="comparison-unavailable">No values from this source were returned in this case. Do not infer a match.</p>}</article>)}</div></section>;
}
