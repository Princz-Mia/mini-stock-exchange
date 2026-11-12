import { GLOSSARY, type Term } from './glossary';

const ORDER_TERMS: readonly Term[] = ['limit', 'protected', 'partial'];
const MONEY_TERMS: readonly Term[] = ['available', 'reserved', 'mark', 'equity'];

function Terms({ terms }: { terms: readonly Term[] }) {
  return (
    <ul>
      {terms.map((term) => (
        <li key={term}>
          <strong>{GLOSSARY[term].term}:</strong> {GLOSSARY[term].definition}
        </li>
      ))}
    </ul>
  );
}

/**
 * A short page on how this exchange works, for either role, from Help. The terms come from the same glossary as the
 * tips beside them on the screens, so the two always say the same.
 */
export function HowItWorks() {
  return (
    <article className="stack how" aria-labelledby="how-title">
      <h1 id="how-title">How this exchange works</h1>

      <section className="panel" aria-labelledby="how-market">
        <h2 id="how-market">A simulated market</h2>
        <p>
          Everything here uses virtual funds: no real money or shares change hands. An operator sets up a market with
          instruments and starting balances, opens it, and can halt and resume it. People and bots trade in it at the same
          time, under the same rules.
        </p>
      </section>

      <section className="panel" aria-labelledby="how-orders">
        <h2 id="how-orders">Orders and trades</h2>
        <p>
          Orders meet in each instrument&apos;s order book: the best price first and, at the same price, the order that
          arrived first. A trade happens at the price of the order that was already waiting, so a buyer whose limit was
          higher pays less than they allowed for. An order never trades with one of your own: it stops there, and the
          rest is cancelled.
        </p>
        <Terms terms={ORDER_TERMS} />
      </section>

      <section className="panel" aria-labelledby="how-money">
        <h2 id="how-money">Your cash and shares</h2>
        <p>
          An order sets aside what it could need before it reaches the book, so nothing can be spent twice. When a trade
          happens, cash and shares change hands at once.
        </p>
        <Terms terms={MONEY_TERMS} />
      </section>

      <section className="panel" aria-labelledby="how-halted">
        <h2 id="how-halted">When trading is halted</h2>
        <p>{GLOSSARY.halted.definition}</p>
      </section>

      <section className="panel" aria-labelledby="how-glossary">
        <h2 id="how-glossary">Glossary</h2>
        <dl className="glossary">
          {(Object.keys(GLOSSARY) as Term[]).map((term) => (
            <div key={term} id={`term-${term}`}>
              <dt>{GLOSSARY[term].term}</dt>
              <dd>{GLOSSARY[term].definition}</dd>
            </div>
          ))}
        </dl>
      </section>
    </article>
  );
}
