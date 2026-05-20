export type TimelineItem = {
  action: string;
  message: string;
  createdAt: string;
};

export function Timeline({ items }: Readonly<{ items: TimelineItem[] }>) {
  return (
    <ol className="timeline">
      {items.map((item) => (
        <li key={`${item.action}-${item.createdAt}`}>
          <strong>{item.action}</strong>
          <span>{item.createdAt}</span>
          <p>{item.message}</p>
        </li>
      ))}
    </ol>
  );
}
