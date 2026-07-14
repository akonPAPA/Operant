// F14 negative fixture: a Server Component page that (transitively) reaches a browser tenant API.
// The transitive RSC guard MUST fail on this chain and print page -> component -> helper -> api.
import { NegativeComponent } from "./component.tsx";
export default function NegativePage() {
  return <NegativeComponent />;
}
